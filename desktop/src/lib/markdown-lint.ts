export interface MarkdownDiagnostic {
  from: number;
  to: number;
  line: number;
  column: number;
  rule: string;
}
export interface MarkdownLintResult {
  issues: MarkdownDiagnostic[];
  truncated: boolean;
}
export const LINT_LIMIT = 200;
export const LINT_RULES = {
  default: false,
  MD009: { br_spaces: 2, strict: false },
  MD018: true,
  MD019: true,
  MD023: true,
  MD037: true,
  MD038: true,
  MD039: true,
  MD042: true,
};
export const ruleDescriptions: Record<string, [string, string]> = {
  MD009: ['多余行尾空格', 'Unexpected trailing spaces'],
  MD018: ['标题符号后缺少空格', 'Missing space after heading marker'],
  MD019: ['标题符号后空格过多', 'Multiple spaces after heading marker'],
  MD023: ['标题不应缩进', 'Heading is indented'],
  MD037: ['强调标记内有多余空白', 'Spaces inside emphasis markers'],
  MD038: ['行内代码边界有多余空白', 'Spaces inside code span'],
  MD039: ['链接文字边界有多余空白', 'Spaces inside link text'],
  MD042: ['链接地址为空', 'Empty link destination'],
};

/** Converts the engine's one-based UTF-16 line/column into editor positions. */
export function diagnosticRange(
  lines: string[],
  starts: number[],
  lineNumber: number,
  range?: readonly number[] | null,
): Pick<MarkdownDiagnostic, 'from' | 'to' | 'line' | 'column'> {
  const row = Math.max(0, Math.min(lines.length - 1, lineNumber - 1));
  const column = Math.max(0, Math.min(lines[row].length, (range?.[0] ?? 1) - 1));
  const length = Math.max(1, range?.[1] ?? lines[row].length);
  const from = starts[row] + column;
  return {
    from,
    to: starts[row] + Math.min(lines[row].length, column + length),
    line: row + 1,
    column: column + 1,
  };
}
