use pulldown_cmark::{CodeBlockKind, Event, Parser, Tag, TagEnd};
use serde::Serialize;
use std::{ops::Range, sync::LazyLock};
use syntect::parsing::{ParseState, Scope, ScopeStack, SyntaxSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum Kind {
    Keyword,
    String,
    Comment,
    Number,
    Name,
}

#[derive(Serialize)]
struct Span {
    start: usize,
    end: usize,
    kind: Kind,
}

#[derive(Serialize)]
struct Block {
    language: String,
    code: String,
    spans: Vec<Span>,
}

#[derive(Default, Serialize)]
pub(crate) struct Highlights {
    source_spans: Vec<Span>,
    blocks: Vec<Block>,
}

static SYNTAXES: LazyLock<SyntaxSet> = LazyLock::new(two_face::syntax::extra_newlines);
static SCOPES: LazyLock<[(Scope, Kind); 7]> = LazyLock::new(|| {
    [
        ("keyword", Kind::Keyword),
        ("storage", Kind::Keyword),
        ("string", Kind::String),
        ("comment", Kind::Comment),
        ("constant.numeric", Kind::Number),
        ("entity.name", Kind::Name),
        ("support.function", Kind::Name),
    ]
    .map(|(scope, kind)| (Scope::new(scope).expect("static scope"), kind))
});

fn highlight_block(language: &str, code: &str) -> Option<Vec<(Range<usize>, Kind)>> {
    if code.len() > 32 * 1024 || code.lines().any(|line| line.len() > 4 * 1024) {
        return None;
    }
    let token = match language {
        "shell" | "bash" => "sh",
        "ps" | "pwsh" => "ps1",
        "csharp" => "cs",
        "docker" => "dockerfile",
        "yml" => "yaml",
        _ => language,
    };
    let syntax = SYNTAXES.find_syntax_by_token(token)?;
    let mut parser = ParseState::new(syntax);
    let mut stack = ScopeStack::new();
    let mut spans = Vec::new();
    let mut offset = 0;
    for line in code.split_inclusive('\n') {
        let changes = parser.parse_line(line, &SYNTAXES).ok()?;
        let text_end = line.strip_suffix('\n').unwrap_or(line).len();
        let mut start = 0;
        for (end, change) in changes
            .iter()
            .map(|(index, change)| ((*index).min(text_end), Some(change)))
            .chain(std::iter::once((text_end, None)))
        {
            if end > start
                && let Some(kind) = stack.scopes.iter().rev().find_map(|scope| {
                    SCOPES
                        .iter()
                        .find_map(|(prefix, kind)| prefix.is_prefix_of(*scope).then_some(*kind))
                })
            {
                spans.push((offset + start..offset + end, kind));
            }
            if let Some(change) = change {
                stack.apply(change).ok()?;
            }
            start = end;
        }
        offset += line.len();
    }
    Some(spans)
}

/// Convert monotonically increasing byte offsets without rescanning the prefix.
fn utf16_offsets(text: &str) -> impl FnMut(usize) -> usize {
    let mut last = 0;
    let mut units = 0;
    move |offset| {
        units += text[last..offset].encode_utf16().count();
        last = offset;
        units
    }
}

pub(crate) fn highlight_markdown(source: &str) -> Highlights {
    let mut result = Highlights::default();
    if source.len() > 128 * 1024 {
        return result;
    }
    let mut source_position = utf16_offsets(source);
    let mut events = Parser::new(source).into_offset_iter();
    while let Some((event, _)) = events.next() {
        let Event::Start(Tag::CodeBlock(CodeBlockKind::Fenced(info))) = event else {
            continue;
        };
        let Some(language) = info.split_whitespace().next().map(str::to_ascii_lowercase) else {
            continue;
        };
        let mut code = String::new();
        let mut segments = Vec::new();
        for (event, range) in events.by_ref() {
            match event {
                Event::End(TagEnd::CodeBlock) => break,
                Event::Text(text) => {
                    // The parser omits enclosing quote/list prefixes and CR bytes.
                    // Map only text whose original bytes can be identified.
                    if !text.is_empty()
                        && let Some(index) = source[range.clone()].find(text.as_ref())
                    {
                        segments.push((code.len()..code.len() + text.len(), range.start + index));
                    }
                    code.push_str(&text);
                }
                _ => {}
            }
        }
        let Some(tokens) = highlight_block(&language, &code) else {
            continue;
        };
        if tokens.is_empty() {
            continue;
        }
        let mut code_position = utf16_offsets(&code);
        let mut spans = Vec::with_capacity(tokens.len());
        let mut segments = segments.into_iter().peekable();
        for (range, kind) in tokens {
            spans.push(Span {
                start: code_position(range.start),
                end: code_position(range.end),
                kind,
            });
            while let Some((segment, original)) = segments.peek() {
                if segment.start >= range.end {
                    break;
                }
                let start = range.start.max(segment.start);
                let end = range.end.min(segment.end);
                if start < end {
                    result.source_spans.push(Span {
                        start: source_position(original + start - segment.start),
                        end: source_position(original + end - segment.start),
                        kind,
                    });
                }
                if segment.end > range.end {
                    break;
                }
                segments.next();
            }
        }
        drop(code_position);
        result.blocks.push(Block {
            language,
            code,
            spans,
        });
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fragments(text: &str, spans: &[Span]) -> Vec<(String, Kind)> {
        let text: Vec<_> = text.encode_utf16().collect();
        spans
            .iter()
            .map(|span| {
                (
                    String::from_utf16(&text[span.start..span.end]).unwrap(),
                    span.kind,
                )
            })
            .collect()
    }

    #[test]
    fn maps_python_tokens_to_both_views_in_quoted_crlf_code() {
        let source = "📝 中文\r\n\r\n> ~~~~PY extra\r\n> # 注释\r\n> def test():\r\n>     return \"🙂\", 42\r\n> ~~~~\r\n";
        let result = highlight_markdown(source);
        let [block] = result.blocks.as_slice() else {
            panic!("expected one Python block")
        };
        let preview = fragments(&block.code, &block.spans);
        let editor = fragments(source, &result.source_spans);
        assert_eq!(editor, preview);
        for (text, kind) in [
            ("def", Kind::Keyword),
            ("🙂", Kind::String),
            ("42", Kind::Number),
        ] {
            assert!(
                preview
                    .iter()
                    .any(|(fragment, actual)| fragment.contains(text) && *actual == kind)
            );
        }
        assert!(
            preview
                .iter()
                .any(|(fragment, kind)| fragment.contains("注释") && *kind == Kind::Comment)
        );
    }

    #[test]
    fn falls_back_for_plain_unknown_and_oversized_blocks() {
        let source = format!(
            "```\nimport sys\n```\n\n```unknown-language\nimport sys\n```\n\n```python\n# {}\n```\n",
            "x".repeat(4096)
        );
        let result = highlight_markdown(&source);
        assert!(result.blocks.is_empty());
        assert!(result.source_spans.is_empty());
    }

    #[test]
    fn recognizes_languages_and_configuration_formats_from_the_pack() {
        for (language, sample) in [
            ("typescript", "const value: string = \"hello\";"),
            ("tsx", "export const element = <div>Hello</div>;"),
            ("kotlin", "fun main() = println(\"hello\")"),
            ("powershell", "Write-Output \"hello\""),
            ("pwsh", "Write-Output \"hello\""),
            ("bash", "if true; then echo \"hello\"; fi"),
            ("dockerfile", "FROM alpine:3.21\nRUN echo \"hello\""),
            ("toml", "title = \"hello\""),
            ("yaml", "title: \"hello\""),
            ("go", "package main\nvar value = 42"),
            ("rust", "fn main() { let value = 42; }"),
            ("sql", "SELECT * FROM notes WHERE id = 42;"),
            ("cpp", "int main() { return 0; }"),
            ("csharp", "class Example { const int Value = 42; }"),
        ] {
            let source = format!("```{language}\n{sample}\n```");
            let result = highlight_markdown(&source);
            assert_eq!(result.blocks.len(), 1, "{language}");
            assert!(!result.source_spans.is_empty(), "{language}");
        }
    }
}
