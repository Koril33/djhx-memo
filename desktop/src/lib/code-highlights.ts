export type CodeSpan = {
  start: number;
  end: number;
  kind: 'keyword' | 'string' | 'comment' | 'number' | 'name';
};

export type CodeHighlights = {
  source_spans: CodeSpan[];
  blocks: { language: string; code: string; spans: CodeSpan[] }[];
};
