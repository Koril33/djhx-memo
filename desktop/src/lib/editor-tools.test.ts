import { afterEach, describe, expect, it, vi } from 'vitest';
import { checkMarkdown } from './lint-engine';
import { LintController, type LintState } from './lint-controller';
import { interpolate } from './split-scroll';
import { renderMarkdown } from './markdown';

describe('optional Markdown suggestions', () => {
  it('keeps free-form notes and hard breaks valid, while locating a real empty link', () => {
    expect(
      checkMarkdown('随记，无标题\n\n' + '很长的文字'.repeat(100) + '  \n下一行').issues,
    ).toEqual([]);
    const source = '😀 第一行\r\n[文字]()\r\n';
    const issue = checkMarkdown(source).issues.find((i) => i.rule === 'MD042')!;
    expect(issue).toBeDefined();
    expect(issue.line).toBe(2);
    expect(source.replace(/\r\n/g, '\n').slice(issue.from, issue.to)).toContain('[文字]()');
    expect(checkMarkdown('```text\n#wrong\n```').issues).toEqual([]);
  });
  it('caps suggestions without misrepresenting a truncated count', () => {
    const result = checkMarkdown(Array.from({ length: 250 }, (_, i) => `[${i}]()`).join('\n\n'));
    expect(result.issues).toHaveLength(200);
    expect(result.truncated).toBe(true);
  });
  it('keeps the fixed rule policy when Markdown contains lint configuration comments', () => {
    expect(
      checkMarkdown('<!-- markdownlint-disable MD042 -->\n\n[empty]()').issues.map((x) => x.rule),
    ).toContain('MD042');
    expect(
      checkMarkdown('<!-- markdownlint-enable MD013 -->\n\n' + 'long '.repeat(100)).issues.map(
        (x) => x.rule,
      ),
    ).not.toContain('MD013');
  });
});

class TestWorker {
  static instances: TestWorker[] = [];
  onmessage?: (event: { data: unknown }) => void;
  onerror?: () => void;
  stopped = false;
  constructor() {
    TestWorker.instances.push(this);
  }
  postMessage() {}
  terminate() {
    this.stopped = true;
  }
}
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  TestWorker.instances = [];
});

describe('lint task ownership', () => {
  it('never loads a worker while disabled/composing and rejects a late old snapshot', () => {
    vi.useFakeTimers();
    vi.stubGlobal('Worker', TestWorker);
    const states: LintState[] = [];
    const task = new LintController((s) => states.push(s));
    task.update('first', false, false);
    vi.advanceTimersByTime(1000);
    task.update('first', true, true);
    vi.advanceTimersByTime(1000);
    expect(TestWorker.instances).toHaveLength(0);
    task.update('first', true, false);
    vi.advanceTimersByTime(750);
    const old = TestWorker.instances[0];
    task.update('second', true, false);
    expect(old.stopped).toBe(true);
    old.onmessage?.({ data: { result: { issues: [{ rule: 'OLD' }], truncated: false } } });
    expect(states.at(-1)?.issues).toEqual([]);
    task.cancel();
  });
  it('pauses large documents, permits a bounded manual attempt, and terminates on timeout', () => {
    vi.useFakeTimers();
    vi.stubGlobal('Worker', TestWorker);
    const states: LintState[] = [];
    const task = new LintController((s) => states.push(s));
    const large = 'x'.repeat(128 * 1024 + 1);
    task.update(large, true, false);
    vi.advanceTimersByTime(1000);
    expect(states.at(-1)?.phase).toBe('paused');
    expect(TestWorker.instances).toHaveLength(0);
    task.update(large, true, false, true);
    vi.advanceTimersByTime(0);
    expect(TestWorker.instances).toHaveLength(1);
    vi.advanceTimersByTime(2000);
    expect(states.at(-1)?.phase).toBe('failed');
    expect(TestWorker.instances[0].stopped).toBe(true);
  });
});

describe('source-aware scroll anchors', () => {
  it('tracks repeated blocks, nested content and normalized source ranges without source attributes', () => {
    const source =
      '# Heading\r\n\r\nrepeat\r\n\r\n- a\r\n  - b\r\n\r\nrepeat\r\n\r\n<script data-source-from="0">bad</script>';
    const html = renderMarkdown(source);
    const ranges = [...html.matchAll(/data-source-from="(\d+)" data-source-to="(\d+)"/g)].map(
      (m) => [Number(m[1]), Number(m[2])],
    );
    const normalized = source.replace(/\r\n/g, '\n');
    expect(ranges.map(([from, to]) => normalized.slice(from, to).trim())).toContain('- a\n  - b');
    expect(
      ranges.filter(([from, to]) => normalized.slice(from, to).trim() === 'repeat'),
    ).toHaveLength(2);
    expect(
      ranges.every(([from, to], i) => from <= to && (i === 0 || from >= ranges[i - 1][1])),
    ).toBe(true);
    expect(html).not.toContain('<script');
    expect(html).toContain('data-source-from=&quot;0&quot;');
  });
  it('interpolates local block positions in both directions and handles boundaries', () => {
    const points = [
      { source: 0, top: 0 },
      { source: 20, top: 300 },
      { source: 100, top: 500 },
    ];
    expect(interpolate(points, 10)).toBe(150);
    expect(interpolate(points, 400, true)).toBe(60);
    expect(interpolate(points, -100)).toBe(0);
    expect(interpolate(points, 200)).toBe(500);
    expect(interpolate([], 10)).toBe(0);
  });
});
