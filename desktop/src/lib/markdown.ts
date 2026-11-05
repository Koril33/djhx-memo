import { Marked, type Tokens, type Token } from 'marked';

export function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  );
}

export function safeUrl(value: string): string | null {
  // No entity/whitespace protocol normalization: only explicit, absolute HTTP(S).
  if (
    !/^https?:\/\//i.test(value) ||
    [...value].some((c) => c.charCodeAt(0) <= 32 || c.charCodeAt(0) === 127)
  )
    return null;
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}

type LocatedCheckbox = Tokens.Checkbox & { sourceLine?: number };

/** Only parser-recognized task items receive a source line. Code/HTML tokens advance past their text. */
function locateTasks(parser: Marked, tokens: Token[], source: string): void {
  const lines = source.split('\n');
  let cursor = 0;
  const comparable = (line: string) => line.trimStart().replace(/^(?:>\s*)+/, '');
  parser.walkTokens(tokens, (token) => {
    if (token.type === 'code' || token.type === 'html') {
      const rawLines = token.raw.replace(/\n$/, '').split('\n');
      const first = comparable(rawLines[0]);
      const index = lines.findIndex((line, i) => i >= cursor && comparable(line) === first);
      if (index >= 0) cursor = index + rawLines.length;
    } else if (token.type === 'list_item' && (token as Tokens.ListItem).task) {
      const item = token as Tokens.ListItem;
      const first = comparable(item.raw.split('\n')[0]);
      const index = lines.findIndex((line, i) => i >= cursor && comparable(line) === first);
      const checkbox = item.tokens.find((t) => t.type === 'checkbox') as
        LocatedCheckbox | undefined;
      if (index >= 0 && checkbox) {
        checkbox.sourceLine = index;
        cursor = index + 1;
      }
    }
  });
}

/** Generated markup is the only HTML admitted to the preview. Raw Markdown HTML is escaped. */
export function renderMarkdown(source: string, interactive = true, previewVersion = ''): string {
  const parser = new Marked({ gfm: true, breaks: false, async: false });
  parser.use({
    renderer: {
      html({ text }: Tokens.HTML | Tokens.Tag) {
        return `<span class="raw-html">${escapeHtml(text)}</span>`;
      },
      image({ text }: Tokens.Image) {
        return `<span class="blocked-image">[图片：${escapeHtml(text || '已禁止自动加载')}]</span>`;
      },
      link({ href, tokens }: Tokens.Link) {
        const label = this.parser.parseInline(tokens);
        const url = safeUrl(href);
        return url
          ? `<a href="${escapeHtml(url)}" rel="noreferrer noopener">${label}</a>`
          : `<span class="blocked-link">${label}</span>`;
      },
      checkbox(token: LocatedCheckbox) {
        if (token.sourceLine === undefined || !interactive)
          return `<span class="task-static">${token.checked ? '☑' : '☐'}</span> `;
        return `<button type="button" class="task-check ${token.checked ? 'checked' : ''}" data-task-line="${token.sourceLine}" data-preview-version="${escapeHtml(previewVersion)}" role="checkbox" aria-checked="${token.checked}" aria-label="${token.checked ? '取消完成' : '标记完成'}">${token.checked ? '✓' : ''}</button> `;
      },
    },
  });
  const tokens = parser.lexer(source);
  locateTasks(parser, tokens, source);
  return parser.parser(tokens);
}
