//! Repeatable search baseline over the maintainer's Markdown blog corpus.
//! Mirrors core's literal-substring recall SQL (`db::search_notes`): FTS5
//! trigram candidate for >=3-char queries, full LIKE scan for shorter ones.
//! Only measures the dominant SQL recall cost; scoring/segments run over the
//! already-matched content and are negligible at this scale.
use rusqlite::Connection;
use std::path::PathBuf;
use std::time::Instant;

const SHORT_SQL: &str = "SELECT id FROM notes WHERE deleted=0 AND content LIKE ?1 ESCAPE '\\'";
const LONG_SQL: &str = "SELECT id FROM notes WHERE deleted=0 AND rowid IN (SELECT rowid FROM notes_fts WHERE content LIKE ?1) AND content LIKE ?1 ESCAPE '\\'";

fn escape(query: &str) -> String {
    query
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
}

fn percentile(mut values: Vec<f64>, p: f64) -> f64 {
    values.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let k = (values.len() - 1) as f64 * p;
    let lo = k.floor() as usize;
    let hi = k.ceil() as usize;
    if lo == hi {
        values[lo]
    } else {
        values[lo] + (values[hi] - values[lo]) * (k - lo as f64)
    }
}

fn collect_notes(dir: &PathBuf) -> Vec<String> {
    let mut notes = Vec::new();
    walk(dir, &mut notes);
    notes
}

fn walk(dir: &PathBuf, notes: &mut Vec<String>) {
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk(&path, notes);
            } else if path.extension().is_some_and(|e| e == "md") {
                if let Ok(text) = std::fs::read_to_string(&path) {
                    notes.push(text);
                }
            }
        }
    }
}

fn main() {
    let blog = std::env::args()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Project\blog"));
    let notes = collect_notes(&blog);
    let bytes: usize = notes.iter().map(|n| n.len()).sum();
    println!(
        "notes={} total_bytes={} total_MiB={:.2}",
        notes.len(),
        bytes,
        bytes as f64 / 1024.0 / 1024.0
    );
    if notes.len() < 50 {
        eprintln!("too few notes");
        std::process::exit(2);
    }

    let conn = Connection::open_in_memory().unwrap();
    conn.execute_batch(
        "CREATE TABLE notes(id TEXT PRIMARY KEY, content TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL);
         CREATE VIRTUAL TABLE notes_fts USING fts5(content, content='notes', content_rowid='rowid', tokenize='trigram');",
    )
    .unwrap();
    for (i, content) in notes.iter().enumerate() {
        conn.execute(
            "INSERT INTO notes(id,content,deleted,updated_at) VALUES(?1,?2,0,?3)",
            rusqlite::params![format!("n{i:05}"), content, "2026-01-01T00:00:00Z"],
        )
        .unwrap();
    }
    // Populate the external-content FTS index (mirrors migration 0002's rebuild).
    conn.execute_batch("INSERT INTO notes_fts(notes_fts) VALUES('rebuild');")
        .unwrap();

    let queries = [
        "的", "会", "会议", "博客", "搜索", "笔记", "the", "hello", "python", "代码", "需要",
        "%", "_", "zzzznotfound",
    ];
    println!("{:<14} {:<6} {:>6} {:>8} {:>8} {:>8}", "query", "path", "hits", "p50ms", "p95ms", "p99ms");
    for query in queries {
        let is_long = query.chars().count() >= 3 && !query.contains(['%', '_', '\\']);
        let sql = if is_long { LONG_SQL } else { SHORT_SQL };
        let pattern = format!("%{}%", escape(query));
        let mut times = Vec::new();
        for _ in 0..50 {
            let start = Instant::now();
            let hits = conn
                .prepare(sql)
                .unwrap()
                .query_map([&pattern], |r| r.get::<_, String>(0))
                .unwrap()
                .count();
            times.push(start.elapsed().as_secs_f64() * 1000.0);
            let _ = hits;
        }
        let hits = conn
            .prepare(sql)
            .unwrap()
            .query_map([&pattern], |r| r.get::<_, String>(0))
            .unwrap()
            .count();
        println!(
            "{:<14} {:<6} {:>6} {:>8.2} {:>8.2} {:>8.2}",
            query,
            if is_long { "FTS" } else { "LIKE" },
            hits,
            percentile(times.clone(), 0.5),
            percentile(times.clone(), 0.95),
            percentile(times, 0.99),
        );
    }
}
