import type { EditorView } from '@codemirror/view';

export interface ScrollPoint {
  source: number;
  top: number;
}

/** Monotonic piecewise interpolation; covers gaps, empty content and endpoints. */
export function interpolate(points: ScrollPoint[], value: number, reverse = false): number {
  if (!points.length) return 0;
  const axis = reverse ? 'top' : 'source';
  const output = reverse ? 'source' : 'top';
  let lo = 0,
    hi = points.length - 1;
  if (value <= points[lo][axis]) return points[lo][output];
  if (value >= points[hi][axis]) return points[hi][output];
  while (lo + 1 < hi) {
    const mid = (lo + hi) >>> 1;
    if (points[mid][axis] <= value) lo = mid;
    else hi = mid;
  }
  const a = points[lo],
    b = points[hi];
  const ratio = (value - a[axis]) / Math.max(1, b[axis] - a[axis]);
  return a[output] + ratio * (b[output] - a[output]);
}

interface Options {
  enabled: boolean;
  view: EditorView | undefined;
  version: string;
}

/** Owns listeners and measurements for one visible split session, not the document. */
export function splitScroll(node: HTMLElement, initial: Options) {
  let options = initial;
  let activeLeader: 'source' | 'preview' = 'source';
  let cleanup = () => {};
  const attach = () => {
    cleanup();
    const view = options.view;
    const preview = node.querySelector<HTMLElement>('.preview-pane');
    if (!options.enabled || !view || !preview) return;
    const source = view.scrollDOM;
    const panes = [source, preview];
    let leader = activeLeader === 'preview' ? preview : source;
    let points: ScrollPoint[] = [];
    let frame = 0,
      measureFrame = 0;
    const expected = new Map<HTMLElement, number>();
    const maximum = (pane: HTMLElement) => Math.max(0, pane.scrollHeight - pane.clientHeight);
    const sourceTop = () => {
      const y = Math.max(0, source.getBoundingClientRect().top - view.documentTop);
      const block = view.lineBlockAtHeight(y);
      return (
        block.from +
        Math.min(1, Math.max(0, (y - block.top) / Math.max(1, block.height))) *
          (block.to - block.from)
      );
    };
    const scrollSource = (position: number) => {
      const block = view.lineBlockAt(
        Math.round(Math.max(0, Math.min(view.state.doc.length, position))),
      );
      const ratio = (position - block.from) / Math.max(1, block.to - block.from);
      return (
        block.top +
        Math.max(0, Math.min(1, ratio)) * block.height +
        view.documentTop -
        source.getBoundingClientRect().top +
        source.scrollTop
      );
    };
    const sync = () => {
      frame = 0;
      if (!points.length || !source.clientHeight || !preview.clientHeight) return;
      const target = leader === source ? preview : source;
      let top =
        leader === source
          ? interpolate(points, sourceTop())
          : scrollSource(interpolate(points, preview.scrollTop, true));
      if (leader.scrollTop <= 0) top = 0;
      else if (maximum(leader) > 0 && leader.scrollTop >= maximum(leader) - 1)
        top = maximum(target);
      top = Math.max(0, Math.min(maximum(target), top));
      if (Math.abs(target.scrollTop - top) < 1) return;
      expected.set(target, top);
      target.scrollTop = top;
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(sync);
    };
    const measure = () => {
      measureFrame = 0;
      const origin = preview.getBoundingClientRect().top - preview.scrollTop;
      points = [...preview.querySelectorAll<HTMLElement>('[data-source-from]')]
        .map((block) => ({
          source: Number(block.dataset.sourceFrom),
          top: block.getBoundingClientRect().top - origin,
        }))
        .filter((point) => Number.isFinite(point.source));
      // The final source endpoint maps to the end of the article, not its bottom padding.
      const article = preview.querySelector('article');
      const bottom = article
        ? article.getBoundingClientRect().bottom - origin
        : preview.scrollHeight;
      points.push({ source: view.state.doc.length, top: bottom });
      schedule();
    };
    const remeasure = () => {
      if (!measureFrame) measureFrame = requestAnimationFrame(measure);
    };
    const takeControl = (event: Event) => {
      leader = event.currentTarget as HTMLElement;
      activeLeader = leader === source ? 'source' : 'preview';
      expected.delete(leader);
    };
    const scroll = (event: Event) => {
      const pane = event.currentTarget as HTMLElement;
      const target = expected.get(pane);
      if (target !== undefined && Math.abs(pane.scrollTop - target) < 2) {
        expected.delete(pane);
        return;
      }
      expected.delete(pane);
      leader = pane;
      activeLeader = pane === source ? 'source' : 'preview';
      schedule();
    };
    for (const pane of panes) {
      pane.addEventListener('scroll', scroll, { passive: true });
      for (const name of ['wheel', 'pointerdown', 'touchstart', 'keydown'])
        pane.addEventListener(name, takeControl, { passive: true });
    }
    const observer = new ResizeObserver(remeasure);
    observer.observe(node);
    observer.observe(source);
    if (preview.firstElementChild) observer.observe(preview.firstElementChild);
    remeasure();
    cleanup = () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      cancelAnimationFrame(measureFrame);
      for (const pane of panes) {
        pane.removeEventListener('scroll', scroll);
        for (const name of ['wheel', 'pointerdown', 'touchstart', 'keydown'])
          pane.removeEventListener(name, takeControl);
      }
    };
  };
  attach();
  return {
    update(next: Options) {
      if (!options.enabled || options.view !== next.view)
        activeLeader = node.querySelector('.preview-pane')?.contains(document.activeElement)
          ? 'preview'
          : 'source';
      options = next;
      attach();
    },
    destroy() {
      cleanup();
    },
  };
}
