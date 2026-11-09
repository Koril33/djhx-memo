import { icons } from './icons';
import { createTranslator, type Language } from './i18n';
import { Marked, type Tokens, type Token } from 'marked';
import type { CodeHighlights } from './code-highlights';

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

// Only the statically bundled, reviewed check icon enters generated preview HTML.
// Markdown and user text never provide a tag, attribute name or SVG path.
function checkIcon(): string {
  const elements = icons.check
    .map(
      ([tag, attributes]) =>
        `<${tag} ${Object.entries(attributes)
          .map(([name, value]) => `${name}="${escapeHtml(value)}"`)
          .join(' ')} />`,
    )
    .join('');
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${elements}</svg>`;
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
export function renderMarkdown(
  source: string,
  interactive = true,
  previewVersion = '',
  language: Language = 'zh-CN',
  highlights?: CodeHighlights,
): string {
  const t = createTranslator(language);
  const parser = new Marked({ gfm: true, breaks: false, async: false });
  const codeBlocks = new Map(
    highlights?.blocks.map((block) => [
      `${block.language}\0${block.code.replace(/\n$/, '')}`,
      block.spans,
    ]),
  );
  parser.use({
    renderer: {
      code({ text, lang }: Tokens.Code) {
        const label = lang?.match(/\S+/)?.[0];
        if (!label) return false;
        const spans = codeBlocks.get(`${label.toLowerCase()}\0${text}`);
        if (!spans?.length) return false;
        let cursor = 0;
        let html = '';
        for (const { start, end, kind } of spans) {
          html += escapeHtml(text.slice(cursor, start));
          html += `<span class="code-token-${kind}">${escapeHtml(text.slice(start, end))}</span>`;
          cursor = end;
        }
        html += escapeHtml(text.slice(cursor).replace(/\n$/, ''));
        return `<pre><code class="language-${escapeHtml(label)}">${html}\n</code></pre>\n`;
      },
      html({ text }: Tokens.HTML | Tokens.Tag) {
        return `<span class="raw-html">${escapeHtml(text)}</span>`;
      },
      image({ text }: Tokens.Image) {
        return `<span class="blocked-image">[${t('imageLabel')}: ${escapeHtml(text || t('imageBlocked'))}]</span>`;
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
          return `<span class="task-static ${token.checked ? 'checked' : ''}" role="checkbox" aria-readonly="true" aria-checked="${token.checked}" aria-label="${token.checked ? t('taskUncheck') : t('taskCheck')}">${token.checked ? checkIcon() : ''}</span> `;
        return `<button type="button" class="task-check ${token.checked ? 'checked' : ''}" data-task-line="${token.sourceLine}" data-preview-version="${escapeHtml(previewVersion)}" role="checkbox" aria-checked="${token.checked}" title="${token.checked ? t('taskUncheck') : t('taskCheck')}" aria-label="${token.checked ? t('taskUncheck') : t('taskCheck')}">${token.checked ? checkIcon() : ''}</button> `;
      },
    },
  });
  const tokens = parser.lexer(source);
  locateTasks(parser, tokens, source);
  return parser.parser(tokens);
}
