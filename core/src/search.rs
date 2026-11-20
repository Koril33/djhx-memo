use serde::Serialize;
use std::ops::Range;

/// A pre-split text run so frontends render highlights without any offset math
/// (Rust UTF-8 bytes vs JavaScript/Kotlin UTF-16 code units never line up).
#[derive(Clone, Debug, Serialize)]
pub(crate) struct Segment {
    pub text: String,
    pub hit: bool,
}

/// One note's local full-text search result. `profile_id` and `space_kind` are
/// filled in by the engine after per-space candidates are merged.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct SearchHit {
    pub note_id: String,
    pub profile_id: String,
    pub space_kind: String,
    pub deleted: bool,
    pub title: String,
    pub title_segments: Vec<Segment>,
    pub snippet: String,
    pub segments: Vec<Segment>,
    pub hit_count: usize,
    pub score: i64,
    pub updated_at: String,
    pub generation: String,
}

/// Characters of context kept on each side of the first match in a snippet.
const SNIPPET_CONTEXT: usize = 40;

/// ASCII case-insensitive literal substring matches as byte ranges into
/// `content`. Non-ASCII code points match exactly (the v1 contract). Ranges are
/// guaranteed to land on UTF-8 character boundaries.
pub(crate) fn find_matches(content: &str, query: &str) -> Vec<Range<usize>> {
    if query.is_empty() || query.len() > content.len() {
        return Vec::new();
    }
    // ASCII folding is byte-length preserving, so offsets in the folded copy
    // are identical to offsets in the original.
    let folded_content = content.to_ascii_lowercase();
    let folded_query = query.to_ascii_lowercase();
    let mut ranges = Vec::new();
    let mut cursor = 0usize;
    while let Some(offset) = folded_content[cursor..].find(folded_query.as_str()) {
        let start = cursor + offset;
        let end = start + folded_query.len();
        ranges.push(start..end);
        cursor = end;
    }
    ranges
}

/// Splits `text` into alternating non-hit / hit runs for the given byte ranges.
pub(crate) fn build_segments(text: &str, ranges: &[Range<usize>]) -> Vec<Segment> {
    let mut segments = Vec::new();
    let mut cursor = 0usize;
    for range in ranges {
        if range.start < cursor || range.start >= text.len() || range.end > text.len() {
            continue;
        }
        if range.start > cursor {
            segments.push(Segment {
                text: text[cursor..range.start].to_string(),
                hit: false,
            });
        }
        segments.push(Segment {
            text: text[range.start..range.end].to_string(),
            hit: true,
        });
        cursor = range.end;
    }
    if cursor < text.len() {
        segments.push(Segment {
            text: text[cursor..].to_string(),
            hit: false,
        });
    }
    segments
}

fn byte_to_char(text: &str, byte: usize) -> usize {
    text[..byte.min(text.len())].chars().count()
}

fn char_to_byte(text: &str, char_index: usize) -> usize {
    text.char_indices()
        .nth(char_index)
        .map(|(byte, _)| byte)
        .unwrap_or(text.len())
}

/// Builds a context window around the first match and pre-splits it into
/// highlight segments. `ranges` must be non-empty byte ranges into `content`.
fn build_snippet(content: &str, query: &str, ranges: &[Range<usize>]) -> (String, Vec<Segment>) {
    let Some(first) = ranges.first() else {
        let head: String = content.chars().take(SNIPPET_CONTEXT * 2).collect();
        return (
            head.clone(),
            vec![Segment {
                text: head,
                hit: false,
            }],
        );
    };
    let char_start = byte_to_char(content, first.start);
    let char_end = byte_to_char(content, first.end);
    let total = content.chars().count();
    let window_start = char_start.saturating_sub(SNIPPET_CONTEXT);
    let window_end = (char_end + SNIPPET_CONTEXT).min(total);
    let window = &content[char_to_byte(content, window_start)..char_to_byte(content, window_end)];
    let window_ranges = find_matches(window, query);
    (window.to_string(), build_segments(window, &window_ranges))
}

/// Deterministic relevance score. Title hits dominate; more occurrences rank
/// higher; an early first match gets a small bonus. `updated_at`/`note_id` are
/// applied as tie-breakers by the caller, not here.
fn score(title_hit: bool, hit_count: usize, first_byte: usize) -> i64 {
    let mut value = 0i64;
    if title_hit {
        value += 1000;
    }
    value += (hit_count.min(20) as i64) * 10;
    if first_byte < 200 {
        value += 20;
    }
    value
}

/// Analyzes one note and produces a hit, or `None` when the literal query does
/// not occur. This runs after candidate recall, so `None` is only possible for
/// the trigram candidate path where the outer `content LIKE` filter is applied
/// by the caller before invoking this function.
pub(crate) fn build_hit(
    note_id: &str,
    content: &str,
    deleted: bool,
    updated_at: &str,
    generation: i64,
    query: &str,
) -> SearchHit {
    let title = crate::model::title(content);
    let ranges = find_matches(content, query);
    let title_ranges = find_matches(&title, query);
    let hit_count = ranges.len();
    let first_byte = ranges.first().map(|r| r.start).unwrap_or(0);
    let title_hit = !title_ranges.is_empty();
    let (snippet, segments) = build_snippet(content, query, &ranges);
    SearchHit {
        note_id: note_id.to_string(),
        profile_id: String::new(),
        space_kind: String::new(),
        deleted,
        title: title.clone(),
        title_segments: build_segments(&title, &title_ranges),
        snippet,
        segments,
        hit_count,
        score: score(title_hit, hit_count, first_byte),
        updated_at: updated_at.to_string(),
        generation: generation.to_string(),
    }
}
