import { describe, expect, it } from 'vitest';
import { renderMarkdown, safeUrl } from './markdown';

describe('restricted Markdown preview', () => {
  it('shows raw HTML as text and never loads images or embeds', () => {
    const html = renderMarkdown(
      '<script>alert(1)</script>\n\n<iframe src="https://evil.test"></iframe>\n\n![beacon](https://evil.test/a.png)',
    );
    expect(html).not.toMatch(/<(script|iframe|img)\b/i);
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('src="');
  });
  it('rejects dangerous protocols and permits only absolute HTTP(S)', () => {
    for (const url of [
      'javascript:alert(1)',
      'data:text/html,test',
      'file:///etc/passwd',
      'tauri://localhost',
      '//evil.test',
      'https://user:pass@test.test',
      'https://test.test/\n',
    ])
      expect(safeUrl(url)).toBeNull();
    expect(safeUrl('https://example.test/a?q=中文')).toContain('https://example.test/');
    expect(renderMarkdown('[bad](javascript:alert%281%29)')).not.toContain('href=');
    expect(renderMarkdown('[ok](https://example.test)')).toContain('rel="noreferrer noopener"');
  });
  it('maps tasks to source lines, excluding fenced and indented code and raw HTML', () => {
    const source =
      '```md\n- [ ] identical\n```\n\n    - [ ] identical\n\n- [ ] identical\n  - [x] 中文\n\n> - [ ] 引用\n';
    const html = renderMarkdown(source);
    expect([...html.matchAll(/data-task-line="(\d+)"/g)].map((m) => Number(m[1]))).toEqual([
      6, 7, 9,
    ]);
    expect(renderMarkdown(source, false)).not.toContain('data-task-line');
  });
  it('uses bundled vector task marks and exposes read-only checked state without action glyphs', () => {
    const source = '- [x] done\n\n<svg><text>untrusted</text></svg>';
    const interactive = renderMarkdown(source, true, 'preview-1', 'en');
    expect(interactive).toContain('role="checkbox" aria-checked="true"');
    expect(interactive).toContain('aria-label="Mark incomplete"');
    expect(interactive).toContain('stroke-width="2"');
    expect(interactive.match(/<svg /g)).toHaveLength(1);
    expect(interactive).toContain('&lt;svg&gt;');
    for (const glyph of ['✓', '☑', '☐']) expect(interactive).not.toContain(glyph);
    const readonly = renderMarkdown(source, false, 'preview-1', 'zh-CN');
    expect(readonly).toContain('aria-readonly="true" aria-checked="true"');
    expect(readonly).not.toContain('<button');
    expect(readonly).not.toContain('data-task-line');
  });
  it('preserves formatting and exposes no IPC attributes from source', () => {
    const html = renderMarkdown(
      '# 标题\n\n**粗体** `- [ ] code`\n\n<button data-task-line="0" onclick="alert(1)">bad</button>',
    );
    expect(html).toContain('<h1>标题</h1>');
    expect(html).toContain('<strong>粗体</strong>');
    expect(html).not.toContain('<button');
  });

  it('colors a matching fenced block and escapes highlighted and unhighlighted code', () => {
    const code = 'const value = "<script>"; // <img>';
    const source = `~~~TypeScript extra\n${code}\n~~~\n\n~~~text\n${code}\n~~~`;
    const html = renderMarkdown(source, true, '', 'en', {
      source_spans: [],
      blocks: [
        {
          language: 'typescript',
          code: code + '\n',
          spans: [
            { start: 0, end: 5, kind: 'keyword' },
            { start: 14, end: 24, kind: 'string' },
          ],
        },
      ],
    });
    expect(html).toContain('<span class="code-token-keyword">const</span>');
    expect(html).toContain('<span class="code-token-string">&quot;&lt;script&gt;&quot;</span>');
    expect(html).toContain('// &lt;img&gt;');
    expect(html).not.toMatch(/<(script|img)\b/);
    expect(html.match(/code-token-keyword/g)).toHaveLength(1);
  });
});
