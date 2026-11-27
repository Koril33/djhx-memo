import { Marked, type Token, type Tokens } from 'marked';

/**
 * A derived heading entry for the outline panel. `line` is the 0-based logical
 * line index after normalizing `\r\n`/`\r` to `\n`; `offset` is the character
 * offset of the heading token in the same normalized source and equals the
 * `data-source-from` attribute that `renderMarkdown` stamps on the heading.
 * Keeping both here avoids recomputing the offset in the UI (single source).
 */
export type Heading = { level: number; label: string; line: number; offset: number };

/** Walk inline tokens to their plain text, dropping emphasis/code/link/image syntax. */
function inlinePlainText(tokens: Token[]): string {
  let out = '';
  for (const token of tokens) {
    switch (token.type) {
      case 'text':
      case 'escape':
      case 'codespan':
        out += (token as Tokens.Text | Tokens.Escape | Tokens.Codespan).text;
        break;
      case 'strong':
      case 'em':
      case 'del':
      case 'link':
      case 'image':
        out += inlinePlainText((token as Tokens.Strong).tokens);
        break;
      case 'br':
        out += ' ';
        break;
      default:
        // Raw HTML and other inline tokens are excluded from the derived label.
        break;
    }
  }
  return out;
}

/** Decode the fixed entity surface that may appear in heading labels, each exactly once. */
function decodeEntities(value: string): string {
  const entities: Record<string, string> = {
    amp: '&',
    lt: '<',
    gt: '>',
    quot: '"',
    '#39': "'",
    nbsp: '\u00a0',
  };
  return value.replace(
    /&(amp|lt|gt|quot|#39|nbsp);/g,
    (match, name: string) => entities[name] ?? match,
  );
}

/**
 * Locate top-level ATX/setext headings. Only the top-level token array is
 * walked, so headings nested inside blockquotes or list items are naturally
 * excluded (as are fenced-code contents). Line endings are normalized first so
 * offsets stay stable across CRLF/CR sources.
 */
export function locateHeadings(source: string): Heading[] {
  source = source.replace(/\r\n?/g, '\n');
  const parser = new Marked({ gfm: true, breaks: false, async: false });
  const tokens = parser.lexer(source);
  const headings: Heading[] = [];
  let position = 0;
  for (const token of tokens) {
    if (token.type === 'heading') {
      const heading = token as Tokens.Heading;
      headings.push({
        level: heading.depth,
        label: decodeEntities(inlinePlainText(heading.tokens)),
        line: source.slice(0, position).split('\n').length - 1,
        offset: position,
      });
    }
    position += token.raw.length;
  }
  return headings;
}
