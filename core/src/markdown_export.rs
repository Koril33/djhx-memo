/// Suggests a portable `.md` filename from the first nonempty source line.
///
/// The source is never rewritten or persisted. The stem is bounded to 180 UTF-8 bytes,
/// excludes filesystem separators/control characters and avoids Windows device names.
#[uniffi::export]
pub fn markdown_export_filename(source: String) -> String {
    let title = crate::model::title(&source);
    let mut stem = String::new();
    for ch in title.chars() {
        let ch = if ch.is_control() || r#"<>:"/\|?*"#.contains(ch) {
            '_'
        } else {
            ch
        };
        if stem.len() + ch.len_utf8() > 180 {
            break;
        }
        stem.push(ch);
    }
    let mut stem = stem.trim().trim_end_matches(['.', ' ']).to_owned();
    if stem.to_ascii_lowercase().ends_with(".md") {
        stem.truncate(stem.len() - 3);
    }
    stem = stem.trim().trim_end_matches(['.', ' ']).to_owned();
    if stem.is_empty() {
        stem = "无标题".into();
    }
    let device = stem.split('.').next().unwrap_or("").to_ascii_uppercase();
    if matches!(device.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ["COM", "LPT"].iter().any(|prefix| {
            device.strip_prefix(prefix).is_some_and(|n| {
                matches!(
                    n,
                    "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
                )
            })
        })
    {
        stem.insert(0, '_');
    }
    while stem.len() > 180 {
        stem.pop();
    }
    format!("{stem}.md")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_are_portable_without_changing_markdown() {
        let source = "\n# 中文😀 / test:*?\n\n```rust\nfn main() {}\n```\n";
        assert_eq!(
            markdown_export_filename(source.into()),
            "中文😀 _ test___.md"
        );
        for name in ["CON", "con.txt", "LPT9", "COM¹"] {
            assert!(markdown_export_filename(name.into()).starts_with('_'));
        }
        assert_eq!(
            markdown_export_filename("# Report.MD... ".into()),
            "Report.md"
        );
        assert_eq!(markdown_export_filename("\n# \n".into()), "无标题.md");
        assert_eq!(markdown_export_filename("../..".into()), ".._.md");
    }
    #[test]
    fn emoji_title_fits_filesystem_byte_limits() {
        let name = markdown_export_filename("😀".repeat(100));
        assert!(name.len() <= 184);
        assert!(name.ends_with(".md"));
        assert!(!name.contains('\u{fffd}'));
    }
}
