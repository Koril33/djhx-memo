import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditorSession } from './editor-session';
import { InvokeError } from './bridge';
import type { CoreCall, Note, Request } from './types';

const note = (content = 'old', generation = '1'): Note => ({
  id: 'note-a',
  content,
  generation,
  title: content,
  deleted: false,
  created_at: '',
  updated_at: '',
  conflict: null,
  save_state: 'saved',
  sync_state: 'local',
});
function setup(handler: (req: Request) => Promise<Note>) {
  const invoke = vi.fn(handler);
  return {
    invoke,
    editor: new EditorSession(
      invoke as CoreCall,
      () => {},
      () => {},
    ),
  };
}
afterEach(() => vi.useRealTimers());

describe('edit commit buffer', () => {
  it('exports the unsaved snapshot after storage failure without adopting later input', async () => {
    const { editor } = setup(async () => {
      throw new Error('fixture storage failure');
    });
    await editor.open(note());
    const text = '# 导出😀\n\n```rust\nfn main() {}\n```\n- [ ] task\n';
    editor.edit(text);
    await expect(editor.flush()).rejects.toThrow('fixture storage failure');
    const snapshot = editor.exportSnapshot();
    editor.edit('later text');
    expect(snapshot).toBe(text);
    editor.setComposing(true);
    expect(() => editor.exportSnapshot()).toThrow(InvokeError);
    editor.setComposing(false);
    expect(editor.exportSnapshot()).toBe('later text');
    editor.dispose();
  });
  it('refuses ordinary export of a pending permanent deletion', async () => {
    const { editor } = setup(async () => note());
    await editor.open({ ...note('old'), purge_state: 'pending' });
    expect(() => editor.exportSnapshot()).toThrow(InvokeError);
    editor.dispose();
  });
  it('keeps an input barrier through a delayed native close acknowledgement', async () => {
    const { editor } = setup(async (req) => note(req.content as string, '2'));
    await editor.open(note());
    editor.edit('final text');
    let release: () => void = () => {};
    const finish = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    const closing = editor.finishLifecycle(finish);
    await vi.waitFor(() => expect(finish).toHaveBeenCalledOnce());
    expect(editor.inputLocked).toBe(true);
    editor.edit('input after the final flush');
    expect(editor.content).toBe('final text');
    expect(editor.unsaved).toBe(false);
    release();
    await closing;
    expect(editor.inputLocked).toBe(false);
    editor.dispose();
  });
  it('drains newer text after retrying a create whose original response was lost', async () => {
    const { editor, invoke } = setup(async (req) => {
      if (invoke.mock.calls.length === 1) throw new Error('response lost after commit');
      if (req.action === 'create') return note('A', '1');
      return note(req.content as string, '2');
    });
    editor.edit('A');
    await expect(editor.flush()).rejects.toThrow('response lost');
    editor.edit('AB');
    await editor.flush();
    expect(invoke.mock.calls[1][0].request_id).toBe(invoke.mock.calls[0][0].request_id);
    expect(invoke.mock.calls[2][0]).toMatchObject({
      action: 'save',
      content: 'AB',
      expected_generation: '1',
    });
    expect(editor.committed).toBe('AB');
    expect(editor.unsaved).toBe(false);
    editor.dispose();
  });
  it('keeps the original generation during IME composition and retains text on concurrent change', async () => {
    const { editor, invoke } = setup(async () => {
      throw new InvokeError({
        code: 'LOCAL_GENERATION_CONFLICT',
        message: 'changed',
        retryable: false,
      });
    });
    await editor.open(note('old', '1'));
    editor.setComposing(true);
    editor.acceptRemote(note('remote body', '9'));
    expect(editor.note?.generation).toBe('1');
    expect(editor.committed).toBe('old');
    editor.edit('old 中文组合完成');
    const flushing = editor.flush();
    await Promise.resolve();
    expect(invoke).not.toHaveBeenCalled();
    editor.setComposing(false);
    await expect(flushing).rejects.toThrow('changed');
    expect(invoke).toHaveBeenCalledWith(
      expect.objectContaining({ expected_generation: '1', content: 'old 中文组合完成' }),
    );
    expect(editor.content).toBe('old 中文组合完成');
    expect(editor.generationConflict).toBe(true);
    editor.dispose();
  });
  it('commits at 300ms and cannot be replaced by a refresh before commit', async () => {
    vi.useFakeTimers();
    const { editor, invoke } = setup(async (req) => note(req.content as string, '2'));
    await editor.open(note());
    editor.edit('中文新输入');
    editor.acceptRemote(note('remote', '8'));
    expect(editor.content).toBe('中文新输入');
    await vi.advanceTimersByTimeAsync(299);
    expect(invoke).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(editor.status).toBe('已保存到本机');
    expect(invoke).toHaveBeenCalledWith(
      expect.objectContaining({
        content: '中文新输入',
        expected_generation: '1',
        profile_id: 'local',
      }),
    );
    editor.dispose();
  });
  it('commits continuous input within one second', async () => {
    vi.useFakeTimers();
    const { editor, invoke } = setup(async (req) =>
      note(req.content as string, String(invoke.mock.calls.length + 1)),
    );
    await editor.open(note());
    for (let i = 0; i < 5; i++) {
      editor.edit(`typing ${i}`);
      await vi.advanceTimersByTimeAsync(200);
    }
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(editor.committed).toBe('typing 4');
    editor.dispose();
  });
  it('serializes edits typed during a commit with the returned generation', async () => {
    let release: (value: Note) => void = () => {};
    const { editor, invoke } = setup(async (req) =>
      invoke.mock.calls.length === 1
        ? new Promise<Note>((resolve) => {
            release = resolve;
          })
        : note(req.content as string, '3'),
    );
    await editor.open(note());
    editor.edit('first');
    const flushing = editor.flush();
    editor.edit('second');
    release(note('first', '2'));
    await flushing;
    expect(invoke.mock.calls[1][0]).toMatchObject({ expected_generation: '2', content: 'second' });
    expect(editor.content).toBe('second');
    expect(editor.unsaved).toBe(false);
    editor.dispose();
  });
  it('retains memory text on storage failure and blocks note switching', async () => {
    const { editor } = setup(async () => {
      throw new Error('DISK_FULL');
    });
    await editor.open(note());
    editor.edit('must survive');
    await expect(editor.open({ ...note('next'), id: 'next' })).rejects.toThrow('DISK_FULL');
    expect(editor.content).toBe('must survive');
    expect(editor.note?.id).toBe('note-a');
    expect(editor.status).toBe('保存失败');
    editor.dispose();
  });
  it('ignores a late list response from before the latest committed generation', async () => {
    const { editor } = setup(async (req) => note(req.content as string, '5'));
    await editor.open(note());
    editor.edit('new commit');
    await editor.flush();
    editor.acceptRemote(note('stale response', '3'));
    expect(editor.content).toBe('new commit');
    expect(editor.note?.generation).toBe('5');
    editor.dispose();
  });
  it('does not overwrite a changed generation and supports explicit rescue as a new note', async () => {
    const { editor, invoke } = setup(async (req) => {
      if (req.action === 'save')
        throw new InvokeError({
          code: 'LOCAL_GENERATION_CONFLICT',
          message: 'changed',
          retryable: false,
        });
      return { ...note(req.content as string), id: 'rescue' };
    });
    await editor.open(note());
    editor.edit('buffer');
    await expect(editor.flush()).rejects.toThrow('changed');
    editor.acceptRemote(note('authoritative', '20'));
    expect(editor.content).toBe('buffer');
    await editor.saveAsCopy();
    expect(editor.note?.id).toBe('rescue');
    expect(invoke.mock.calls[1][0].action).toBe('create');
    editor.dispose();
  });
  it('does not create a whitespace draft, but preserves an existing empty note', async () => {
    const { editor, invoke } = setup(async (req) => note(req.content as string, '2'));
    editor.edit('  \n');
    await editor.flush();
    expect(invoke).not.toHaveBeenCalled();
    await editor.open(note());
    editor.edit('');
    await editor.flush();
    expect(invoke.mock.calls[0][0]).toMatchObject({ action: 'save', content: '' });
    editor.dispose();
  });
});

describe('remote permanent deletion', () => {
  it('retains uncommitted text, stops old writes and requires explicit rescue with a new ID', async () => {
    const { editor, invoke } = setup(async (req) => ({
      ...note(req.content as string, '1'),
      id: 'new-note',
    }));
    await editor.open(note('saved', '1'));
    editor.edit('unsaved rescue text');
    editor.acceptRemote({ ...note('', '2'), deleted: true, purge_state: 'purged' });
    expect(editor.content).toBe('unsaved rescue text');
    expect(editor.generationConflict).toBe(true);
    await expect(editor.flush()).rejects.toBeInstanceOf(InvokeError);
    expect(invoke).not.toHaveBeenCalled();
    await editor.saveAsCopy();
    expect(invoke).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'create', content: 'unsaved rescue text' }),
    );
    expect(editor.note?.id).toBe('new-note');
    editor.dispose();
  });
});
