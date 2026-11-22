import { lint } from 'markdownlint/sync';
import { diagnosticRange, LINT_LIMIT, LINT_RULES, type MarkdownLintResult } from './markdown-lint';

/** Runs only bundled rules against an in-memory document; no files or user code. */
export function checkMarkdown(source: string): MarkdownLintResult {
  const normalized = source.replace(/\r\n?/g, '\n');
  const lines = normalized.split('\n');
  let offset = 0;
  const starts = lines.map((line) => {
    const start = offset;
    offset += line.length + 1;
    return start;
  });
  const errors = lint({
    strings: { note: normalized },
    config: LINT_RULES,
    noInlineConfig: true,
  }).note;
  return {
    truncated: errors.length > LINT_LIMIT,
    issues: errors.slice(0, LINT_LIMIT).map((error) => ({
      ...diagnosticRange(lines, starts, error.lineNumber, error.errorRange),
      rule: error.ruleNames[0],
    })),
  };
}
