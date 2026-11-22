import { checkMarkdown } from './lint-engine';

self.onmessage = (event: MessageEvent<{ source: string }>) => {
  try {
    self.postMessage({ result: checkMarkdown(event.data.source) });
  } catch {
    self.postMessage({ failed: true });
  }
};
