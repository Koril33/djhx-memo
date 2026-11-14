//! Durable, generation-checked list operations; no changes to wire sync semantics.
use crate::{Engine, db, model::*};
use base64::{Engine as _, prelude::BASE64_STANDARD};
use rusqlite::{Connection, TransactionBehavior};
use serde_json::{Value, json};

impl Engine {
    pub(crate) fn batch_deleted(&self, request: &Value, profile: &Profile) -> Result<Value> {
        let items = request["items"]
            .as_array()
            .filter(|v| v.len() <= 10_000)
            .ok_or_else(|| AppError::new("INVALID_REQUEST", "批次无效"))?;
        let deleted = request["deleted"]
            .as_bool()
            .ok_or_else(|| AppError::new("INVALID_REQUEST", "缺少删除状态"))?;
        let mut seen = std::collections::HashSet::new();
        let mut results = Vec::with_capacity(items.len());
        for item in items {
            let result = (|| {
                let id = text(item, "id")?;
                if !seen.insert(id.clone()) {
                    return Err(AppError::new("INVALID_REQUEST", "批次中存在重复笔记"));
                }
                let _guard = self.writable(profile)?;
                let mut database = db::open(&self.path(profile))?;
                let note = set_deleted(
                    &mut database,
                    &id,
                    counter(&text(item, "expected_generation")?)?,
                    deleted,
                    profile.kind == "account",
                )?;
                Ok(note.view(profile.kind == "account", db::blocked(&database, &note.id)))
            })();
            match result {
                Ok(note) => {
                    self.changed(profile);
                    results.push(json!({"id":item["id"],"ok":true,"note":note}));
                }
                Err(error) => results.push(json!({"id":item["id"],"ok":false,"error":error})),
            }
        }
        Ok(json!({"results":results}))
    }

    pub(crate) fn import_markdown(&self, request: &Value, profile: &Profile) -> Result<Value> {
        let name = text(request, "filename")?;
        if !name.to_ascii_lowercase().ends_with(".md") {
            return Err(AppError::new(
                "IMPORT_INVALID_FILE",
                "请选择单个 Markdown 文件",
            ));
        }
        let encoded = text(request, "bytes_base64")?;
        const RAW_LIMIT: usize = MAX_CONTENT * 2 + 3;
        if encoded.len() > RAW_LIMIT.div_ceil(3) * 4 {
            return Err(AppError::new("NOTE_TOO_LARGE", "Markdown 文件超过大小限制"));
        }
        let bytes = BASE64_STANDARD
            .decode(encoded)
            .map_err(|_| AppError::new("IMPORT_INVALID_ENCODING", "文件不是有效 UTF-8"))?;
        if bytes.len() > RAW_LIMIT {
            return Err(AppError::new("NOTE_TOO_LARGE", "Markdown 文件超过大小限制"));
        }
        let source = std::str::from_utf8(&bytes)
            .map_err(|_| AppError::new("IMPORT_INVALID_ENCODING", "文件不是有效 UTF-8"))?;
        let normalized = content(source.strip_prefix('\u{feff}').unwrap_or(source).into())?;
        if normalized.trim().is_empty() {
            return Err(AppError::new("IMPORT_EMPTY", "Markdown 文件内容为空"));
        }
        let _guard = self.writable(profile)?;
        let mut database = db::open(&self.path(profile))?;
        let tx = database.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let note = db::create(
            &tx,
            normalized,
            &text(request, "request_id")?,
            profile.kind == "account",
        )?;
        tx.commit()?;
        self.changed(profile);
        Ok(note.view(profile.kind == "account", db::blocked(&database, &note.id)))
    }
}

fn set_deleted(
    database: &mut Connection,
    id: &str,
    expected: i64,
    deleted: bool,
    account: bool,
) -> Result<LocalNote> {
    let tx = database.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let mut note = db::note(&tx, id)?;
    match note.purge_state.as_str() {
        "purged" => return Err(AppError::new("NOTE_PURGED", "笔记已被彻底删除")),
        "pending" => return Err(AppError::new("PURGE_PENDING", "笔记正在等待彻底删除")),
        _ => {}
    }
    // List undo is tied to the original identity: a redirect must never restore
    // a conflict copy, nor may a retry use a freshly read generation.
    if note.generation != expected || note.deleted == deleted {
        return Err(AppError::new(
            "LOCAL_GENERATION_CONFLICT",
            "笔记状态已变化，请重新读取",
        ));
    }
    if note.generation == note.acknowledged
        && let Some(shadow) = db::shadow(&tx, &note.id)?
    {
        note.base_rev = counter(&shadow.rev)?;
    }
    note.purge_state = "none".into();
    note.deleted = deleted;
    note.generation += 1;
    note.updated_at = now();
    if !account {
        note.acknowledged = note.generation;
    }
    db::put(&tx, &note)?;
    tx.commit()?;
    Ok(note)
}
