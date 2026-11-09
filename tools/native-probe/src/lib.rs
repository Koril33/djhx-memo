//! Disposable SQLite and UniFFI interoperability probe.

#![warn(missing_docs)]

use rusqlite::Connection;

uniffi::setup_scaffolding!();

/// Receives a signal after the probe transaction commits.
#[uniffi::export(callback_interface)]
pub trait CommitListener: Send + Sync {
    /// Reports a committed write.
    fn committed(&self);
}

/// An error in the native persistence probe.
#[derive(Debug, uniffi::Error)]
pub enum ProbeError {
    /// SQLite could not open, write, or query the probe database.
    Database,
}

impl std::fmt::Display for ProbeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("probe database operation failed")
    }
}

impl std::error::Error for ProbeError {}

impl From<rusqlite::Error> for ProbeError {
    fn from(_: rusqlite::Error) -> Self {
        Self::Database
    }
}

/// Writes a synthetic fixture to a disposable database and reads it via FTS5
/// after reopening the connection.
#[uniffi::export]
pub fn probe(path: String, listener: Box<dyn CommitListener>) -> Result<String, ProbeError> {
    let mut database = Connection::open(&path)?;
    let transaction = database.transaction()?;
    transaction.execute_batch(
        "CREATE VIRTUAL TABLE IF NOT EXISTS native_probe USING fts5(content, tokenize='trigram');
         INSERT OR REPLACE INTO native_probe(rowid, content) VALUES (1, '中文持久化探针');",
    )?;
    transaction.commit()?;
    drop(database);
    listener.committed();

    let reopened = Connection::open(path)?;
    Ok(reopened.query_row(
        "SELECT content FROM native_probe WHERE native_probe MATCH ?1",
        ["持久化"],
        |row| row.get(0),
    )?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Counter(Arc<AtomicUsize>);

    impl CommitListener for Counter {
        fn committed(&self) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }

    #[test]
    fn writes_reopens_and_reports_only_committed_writes() {
        let directory =
            std::env::temp_dir().join(format!("djhx-native-probe-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let database = directory.join("probe.sqlite");
        let count = Arc::new(AtomicUsize::new(0));

        for expected_count in 1..=2 {
            let result = probe(
                database.to_str().unwrap().to_owned(),
                Box::new(Counter(count.clone())),
            );
            assert_eq!(result.unwrap(), "中文持久化探针");
            assert_eq!(count.load(Ordering::Relaxed), expected_count);
        }

        let error = probe(
            directory.to_str().unwrap().to_owned(),
            Box::new(Counter(count.clone())),
        );
        assert!(matches!(error, Err(ProbeError::Database)));
        assert_eq!(count.load(Ordering::Relaxed), 2);
        std::fs::remove_file(database).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
}
