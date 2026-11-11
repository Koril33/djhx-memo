import { InvokeError } from './bridge';
import type { CoreCall, Note } from './types';

/** Only an uncommitted text buffer lives here. Revisions and durable outbox belong to core. */
export class EditorSession {
  profileId = 'local';
  note: Note | null = null;
  content = '';
  committed = '';
  saving = false;
  composing = false;
  inputLocked = false;
  error: unknown = null;
  generationConflict = false;
  private requestId = crypto.randomUUID();
  private debounce: ReturnType<typeof setTimeout> | undefined;
  private deadline: ReturnType<typeof setTimeout> | undefined;
  private flight: Promise<void> | null = null;
  private compositionWait: Promise<void> | null = null;
  private endComposition: (() => void) | null = null;

  constructor(
    private invoke: CoreCall,
    private changed: () => void,
    private saved: () => void,
  ) {}

  get unsaved(): boolean {
    return this.content !== this.committed;
  }
  get status(): string {
    if (this.error) return '保存失败';
    if (this.saving || this.unsaved || this.composing) return '保存中';
    return this.note ? '已保存到本机' : '空白草稿';
  }

  async open(note: Note | null): Promise<void> {
    await this.flush();
    this.note = note;
    this.content = note?.content ?? '';
    this.committed = this.content;
    this.requestId = crypto.randomUUID();
    this.error = null;
    this.generationConflict = false;
    this.changed();
  }

  edit(content: string): void {
    if (this.inputLocked) {
      this.changed();
      return;
    }
    this.content = content.replace(/\r\n?/g, '\n');
    this.changed();
    if (this.debounce) clearTimeout(this.debounce);
    this.debounce = setTimeout(() => {
      void this.flush().catch(() => {});
    }, 300);
    this.deadline ??= setTimeout(() => {
      void this.flush().catch(() => {});
    }, 1000);
  }

  setComposing(active: boolean): void {
    if (active === this.composing) return;
    this.composing = active;
    if (active) {
      this.compositionWait = new Promise((resolve) => {
        this.endComposition = resolve;
      });
    } else {
      const finish = this.endComposition;
      this.compositionWait = null;
      this.endComposition = null;
      finish?.();
    }
    this.changed();
  }

  /** Background refresh must never replace input which lacks a commit acknowledgement. */
  acceptRemote(note: Note): void {
    if (this.note?.id === note.id && note.purge_state === 'purged') {
      this.clearTimers();
      const rescue = this.unsaved || this.saving || this.composing;
      this.note = note;
      if (rescue) {
        this.error = new InvokeError({ code: 'NOTE_PURGED', message: '', retryable: false });
        this.generationConflict = true;
      } else {
        this.content = '';
        this.committed = '';
      }
      this.changed();
      return;
    }
    if (this.note?.id !== note.id || this.unsaved || this.saving || this.composing || this.error)
      return;
    if (
      BigInt(note.generation) <= BigInt(this.note.generation) &&
      note.purge_state === this.note.purge_state
    )
      return;
    this.note = note;
    this.content = note.content;
    this.committed = note.content;
    this.changed();
  }

  flush(): Promise<void> {
    this.clearTimers();
    if (this.compositionWait) return this.compositionWait.then(() => this.flush());
    if (this.flight) return this.flight;
    this.flight = this.drain().finally(() => {
      this.flight = null;
    });
    return this.flight;
  }

  /** Freeze new input only after the IME commits, through the native hide/quit acknowledgement. */
  async finishLifecycle(finish: () => Promise<unknown>): Promise<void> {
    if (this.compositionWait) await this.compositionWait;
    this.inputLocked = true;
    this.changed();
    try {
      await this.flush();
      await finish();
    } finally {
      this.inputLocked = false;
      this.changed();
    }
  }

  private async drain(): Promise<void> {
    if (this.generationConflict) throw this.error;
    while (this.unsaved) {
      // A whitespace-only draft is deliberately not a note. Existing notes can be empty.
      if (!this.note && !this.content.trim()) {
        this.committed = this.content;
        break;
      }
      if (this.note?.deleted) throw new Error('回收站笔记需先恢复才能编辑。');
      const snapshot = this.content;
      this.saving = true;
      this.error = null;
      this.changed();
      try {
        const result = await this.invoke<Note>(
          this.note
            ? {
                action: 'save',
                profile_id: this.profileId,
                id: this.note.id,
                expected_generation: this.note.generation,
                content: snapshot,
              }
            : {
                action: 'create',
                profile_id: this.profileId,
                content: snapshot,
                request_id: this.requestId,
              },
        );
        if (this.note?.purge_state === 'purged')
          throw new InvokeError({ code: 'NOTE_PURGED', message: '', retryable: false });
        this.note = result;
        // An idempotent create retry can return the first committed text, not this retry's text.
        this.committed = result.content;
        this.saved();
      } catch (error) {
        this.error = error;
        this.generationConflict =
          error instanceof InvokeError &&
          ['LOCAL_GENERATION_CONFLICT', 'NOTE_PURGED'].includes(error.detail.code);
        throw error;
      } finally {
        this.saving = false;
        this.changed();
      }
    }
    this.changed();
  }

  /** Explicit rescue: keep the authoritative note intact and save this buffer under a new ID. */
  async saveAsCopy(): Promise<void> {
    if (this.flight) await this.flight.catch(() => {});
    this.note = null;
    this.committed = '';
    this.requestId = crypto.randomUUID();
    this.generationConflict = false;
    this.error = null;
    this.changed();
    await this.flush();
  }

  dispose(): void {
    this.clearTimers();
  }
  private clearTimers(): void {
    clearTimeout(this.debounce);
    clearTimeout(this.deadline);
    this.debounce = undefined;
    this.deadline = undefined;
  }
}
