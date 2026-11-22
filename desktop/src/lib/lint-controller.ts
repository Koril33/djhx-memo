import { isLargeNote } from './note-size';
import type { MarkdownLintResult } from './markdown-lint';

export type LintPhase = 'off' | 'pending' | 'checking' | 'ready' | 'paused' | 'failed';
export interface LintState extends MarkdownLintResult {
  phase: LintPhase;
}

/** One worker per immutable editor snapshot; cancellation really terminates computation. */
export class LintController {
  private timer: ReturnType<typeof setTimeout> | undefined;
  private deadline: ReturnType<typeof setTimeout> | undefined;
  private worker: Worker | undefined;
  private serial = 0;
  constructor(private publish: (state: LintState) => void) {}

  update(source: string, enabled: boolean, composing: boolean, manual = false): void {
    this.cancel();
    const phase = !enabled
      ? 'off'
      : composing
        ? 'pending'
        : !manual && isLargeNote(source)
          ? 'paused'
          : 'pending';
    this.publish({ phase, issues: [], truncated: false });
    if (phase !== 'pending' || composing) return;
    const serial = this.serial;
    this.timer = setTimeout(
      () => {
        this.publish({ phase: 'checking', issues: [], truncated: false });
        const finish = (state: LintState) => {
          if (serial !== this.serial) return;
          this.cancel();
          this.publish(state);
        };
        try {
          const worker = new Worker(new URL('./lint-worker.ts', import.meta.url), {
            type: 'module',
          });
          this.worker = worker;
          worker.onmessage = (event: MessageEvent<{ result?: MarkdownLintResult }>) => {
            finish(
              event.data.result
                ? { ...event.data.result, phase: 'ready' }
                : { phase: 'failed', issues: [], truncated: false },
            );
          };
          worker.onerror = () => finish({ phase: 'failed', issues: [], truncated: false });
          this.deadline = setTimeout(
            () => finish({ phase: 'failed', issues: [], truncated: false }),
            2000,
          );
          worker.postMessage({ source });
        } catch {
          finish({ phase: 'failed', issues: [], truncated: false });
        }
      },
      manual ? 0 : 750,
    );
  }

  cancel(): void {
    ++this.serial;
    clearTimeout(this.timer);
    clearTimeout(this.deadline);
    this.worker?.terminate();
    this.worker = undefined;
  }
}
