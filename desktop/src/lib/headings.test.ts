import { describe, expect, it } from 'vitest';
import { locateHeadings } from './headings';
import { renderMarkdown } from './markdown';

/** Drop the offset so existing structural assertions stay terse. */
function withoutOffset(headings: ReturnType<typeof locateHeadings>) {
  return headings.map(({ level, label, line }) => ({ level, label, line }));
}

describe('derived Markdown headings', () => {
  it('locates ATX and setext headings with 0-based logical line indices', () => {
    const source = '# One\n\nTwo\n===\n\n### Three\n';
    expect(withoutOffset(locateHeadings(source))).toEqual([
      { level: 1, label: 'One', line: 0 },
      { level: 1, label: 'Two', line: 2 },
      { level: 3, label: 'Three', line: 5 },
    ]);
  });
  it('normalizes CRLF and lone CR before computing line indices', () => {
    const source = '# A\r\n\r\nB\r\n---\r\n\r\n## C\r';
    expect(withoutOffset(locateHeadings(source))).toEqual([
      { level: 1, label: 'A', line: 0 },
      { level: 2, label: 'B', line: 2 },
      { level: 2, label: 'C', line: 5 },
    ]);
  });
  it('excludes fenced code, blockquote and list-contained headings', () => {
    const source = '> # quote\n\n- # list item\n\n```\n# fence\n```\n\n# real\n';
    expect(withoutOffset(locateHeadings(source))).toEqual([{ level: 1, label: 'real', line: 8 }]);
  });
  it('strips inline emphasis, code, links and images and decodes entities', () => {
    const source =
      '# **bold** *em* `code` [link](https://x.test) ![alt](https://y.test) &amp; &lt;tag&gt; &#39;q&#39; &quot;d&quot; &nbsp;';
    expect(withoutOffset(locateHeadings(source))).toEqual([
      { level: 1, label: 'bold em code link alt & <tag> \'q\' "d" \u00a0', line: 0 },
    ]);
  });
  it('keeps deterministic plain-text labels across heading depths', () => {
    const source = '###### deep\n\n## **a** &amp; b\n';
    expect(withoutOffset(locateHeadings(source))).toEqual([
      { level: 6, label: 'deep', line: 0 },
      { level: 2, label: 'a & b', line: 2 },
    ]);
  });
  it('exposes offsets that match the rendered data-source-from attributes', () => {
    const source = '# One\n\nparagraph\n\nTwo\n===\n\n### Three\n\n- a\n- b\n\n> quote\n';
    const headings = locateHeadings(source);
    const html = renderMarkdown(source);
    // `renderMarkdown` stamps data-source-from on every top-level block; the
    // heading elements are the exact set the outline must be able to hit.
    const headingOffsets = [...html.matchAll(/<h[1-6][^>]*data-source-from="(\d+)"/g)].map(
      (match) => Number(match[1]),
    );
    expect(headingOffsets).toEqual(headings.map((heading) => heading.offset));
  });
});
