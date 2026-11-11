use crate::model::*;
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;

const MIGRATIONS: &[&str] = &[
    include_str!("../migrations/0001_local.sql"),
    include_str!("../migrations/0002_search.sql"),
    include_str!("../migrations/0003_permanent_deletion.sql"),
];

pub(crate) fn open(path: &Path) -> Result<Connection> {
    let mut db = Connection::open(path)?;
    db.busy_timeout(std::time::Duration::from_secs(5))?;
    db.pragma_update(None, "foreign_keys", true)?;
    db.pragma_update(None, "synchronous", "FULL")?;
    let version: i64 = db.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if version > MIGRATIONS.len() as i64 {
        return Err(AppError::new(
            "LOCAL_SCHEMA_TOO_NEW",
            "数据库来自更新版本，请升级应用",
        ));
    }
    db.pragma_update(None, "journal_mode", "WAL")?;
    db.execute_batch("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)")?;
    for (i, sql) in MIGRATIONS.iter().enumerate() {
        let next = (i + 1) as i64;
        let checksum = format!("{:x}", Sha256::digest(sql.as_bytes()));
        if next <= version {
            let saved: Option<String> = db
                .query_row(
                    "SELECT checksum FROM schema_migrations WHERE version=?",
                    [next],
                    |r| r.get(0),
                )
                .optional()?;
            if saved.as_deref() != Some(&checksum) {
                return Err(AppError::new(
                    "LOCAL_STORAGE_FAILED",
                    "数据库迁移校验失败，已停止写入",
                ));
            }
        } else {
            if version > 0 && i as i64 == version {
                let backup = path.with_extension(format!("before-v{next}.sqlite"));
                if !backup.exists() {
                    db.backup(
                        rusqlite::MAIN_DB,
                        &backup,
                        None::<fn(rusqlite::backup::Progress)>,
                    )?;
                    private_file(&backup)?;
                }
            }
            let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
            tx.execute_batch(sql)?;
            tx.execute(
                "INSERT INTO schema_migrations VALUES(?1,?2,?3)",
                params![next, checksum, now()],
            )?;
            tx.pragma_update(None, "user_version", next)?;
            tx.commit()?;
        }
    }
    private_file(path)?;
    Ok(db)
}

pub(crate) fn private_file(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    let _ = path;
    Ok(())
}

fn row_note(row: &rusqlite::Row<'_>) -> rusqlite::Result<LocalNote> {
    let conflict: Option<String> = row.get(8)?;
    Ok(LocalNote {
        id: row.get(0)?,
        content: row.get(1)?,
        deleted: row.get(2)?,
        purge_state: row.get(9)?,
        generation: row.get(3)?,
        acknowledged: row.get(4)?,
        base_rev: row.get(5)?,
        created_at: row.get(6)?,
        updated_at: row.get(7)?,
        conflict: conflict.and_then(|s| serde_json::from_str(&s).ok()),
    })
}
pub(crate) fn note(db: &Connection, id: &str) -> Result<LocalNote> {
    db.query_row("SELECT id,content,deleted,generation,acknowledged,base_rev,created_at,updated_at,conflict,purge_state FROM notes WHERE id=?",[id],row_note).optional()?.ok_or_else(||AppError::new("NOT_FOUND","笔记不存在"))
}
pub(crate) fn list(db: &Connection, query: &str, deleted: bool) -> Result<Vec<LocalNote>> {
    // LIKE is parameterized and escaped; trigram LIKE optimization is only used
    // when SQLite can preserve exactly the same literal substring semantics.
    let escaped = query
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_");
    let sql = if query.chars().count() >= 3 && !query.contains(['%', '_', '\\']) {
        r"SELECT id,content,deleted,generation,acknowledged,base_rev,created_at,updated_at,conflict,purge_state FROM notes WHERE purge_state!='purged' AND deleted=?1 AND rowid IN (SELECT rowid FROM notes_fts WHERE content LIKE ?2) AND content LIKE ?2 ESCAPE '\' ORDER BY updated_at DESC,id ASC"
    } else {
        r"SELECT id,content,deleted,generation,acknowledged,base_rev,created_at,updated_at,conflict,purge_state FROM notes WHERE purge_state!='purged' AND deleted=?1 AND content LIKE ?2 ESCAPE '\' ORDER BY updated_at DESC,id ASC"
    };
    let mut statement = db.prepare(sql)?;
    let values = statement
        .query_map(params![deleted, format!("%{escaped}%")], row_note)?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(values)
}
pub(crate) fn put(db: &Connection, n: &LocalNote) -> Result<()> {
    db.execute("INSERT INTO notes(id,content,deleted,generation,acknowledged,base_rev,created_at,updated_at,conflict,purge_state) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10) ON CONFLICT(id) DO UPDATE SET content=excluded.content,deleted=excluded.deleted,generation=excluded.generation,acknowledged=excluded.acknowledged,base_rev=excluded.base_rev,created_at=excluded.created_at,updated_at=excluded.updated_at,conflict=excluded.conflict,purge_state=excluded.purge_state",params![n.id,n.content,n.deleted,n.generation,n.acknowledged,n.base_rev,n.created_at,n.updated_at,n.conflict.as_ref().map(serde_json::to_string).transpose()?,n.purge_state])?;
    Ok(())
}
pub(crate) fn get_state(db: &Connection, key: &str) -> Result<Option<String>> {
    Ok(db
        .query_row("SELECT value FROM sync_state WHERE key=?", [key], |r| {
            r.get(0)
        })
        .optional()?)
}
pub(crate) fn set_state(db: &Connection, key: &str, value: &str) -> Result<()> {
    db.execute(
        "INSERT INTO sync_state VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        params![key, value],
    )?;
    Ok(())
}
pub(crate) fn shadow(db: &Connection, id: &str) -> Result<Option<WireNote>> {
    let serialized: Option<String> = db
        .query_row("SELECT snapshot FROM note_shadows WHERE id=?", [id], |r| {
            r.get(0)
        })
        .optional()?;
    serialized
        .map(|s| serde_json::from_str(&s).map_err(AppError::from))
        .transpose()
}
pub(crate) fn set_shadow(db: &Connection, n: &WireNote) -> Result<()> {
    if let Some(previous) = shadow(db, &n.id)?
        && counter(&previous.rev)? > counter(&n.rev)?
    {
        return Ok(());
    }
    db.execute("INSERT INTO note_shadows VALUES(?1,?2) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot",params![n.id,serde_json::to_string(n)?])?;
    Ok(())
}
pub(crate) fn blocked(db: &Connection, id: &str) -> bool {
    db.query_row(
        "SELECT blocked IS NOT NULL FROM outbox WHERE note_id=?",
        [id],
        |r| r.get(0),
    )
    .unwrap_or(false)
}
pub(crate) fn pending(db: &Connection) -> Result<usize> {
    Ok(db.query_row("SELECT count(*) FROM notes n WHERE purge_state!='purged' AND (EXISTS(SELECT 1 FROM purge_requests p WHERE p.note_id=n.id) OR (generation>acknowledged AND NOT (deleted=1 AND base_rev=0 AND NOT EXISTS(SELECT 1 FROM outbox WHERE note_id=n.id))))",[],|r|r.get::<_,i64>(0))? as usize)
}
pub(crate) fn freeze(db: &mut Connection) -> Result<Option<(Operation, i64)>> {
    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let frozen:Option<(String,i64)>=tx.query_row("SELECT snapshot,frozen_generation FROM outbox WHERE blocked IS NULL ORDER BY rowid LIMIT 1",[],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
    if let Some((json, generation)) = frozen {
        return Ok(Some((serde_json::from_str(&json)?, generation)));
    }
    let candidate:Option<String>=tx.query_row("SELECT id FROM notes n WHERE purge_state!='purged' AND generation>acknowledged AND NOT EXISTS(SELECT 1 FROM outbox WHERE note_id=n.id) AND NOT(deleted=1 AND base_rev=0) ORDER BY updated_at,id LIMIT 1",[],|r|r.get(0)).optional()?;
    let Some(id) = candidate else {
        return Ok(None);
    };
    let n = note(&tx, &id)?;
    let op = Operation {
        op_id: crate::model::id(),
        note_id: n.id,
        base_rev: n.base_rev.to_string(),
        content: n.content,
        deleted: n.deleted,
    };
    tx.execute(
        "INSERT INTO outbox(op_id,note_id,snapshot,frozen_generation) VALUES(?1,?2,?3,?4)",
        params![
            op.op_id,
            op.note_id,
            serde_json::to_string(&op)?,
            n.generation
        ],
    )?;
    tx.commit()?;
    Ok(Some((op, n.generation)))
}

pub(crate) fn apply_page(
    db: &mut Connection,
    notes: &[WireNote],
    cursor: &str,
    until: &str,
    fail_before_commit: bool,
) -> Result<()> {
    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    for wire in notes {
        if !valid_id(&wire.id) || wire.content.len() > MAX_CONTENT || counter(&wire.rev)? < 1 {
            return Err(AppError::new("INVALID_REQUEST", "服务器返回无效笔记"));
        }
        if let Some(previous) = shadow(&tx, &wire.id)? {
            let known_revision = counter(&previous.rev)?;
            let incoming_revision = counter(&wire.rev)?;
            if known_revision > incoming_revision {
                continue;
            }
            if previous.purged && !wire.purged {
                return Err(AppError::new("INVALID_REQUEST", "永久删除状态不可逆"));
            }
            if known_revision == incoming_revision
                && (previous.content != wire.content
                    || previous.deleted != wire.deleted
                    || previous.purged != wire.purged)
            {
                return Err(AppError::new(
                    "INVALID_REQUEST",
                    "同一权威版本出现不同内容，原状态已保留",
                ));
            }
        }
        if wire.purged {
            crate::purge::install(&tx, wire)?;
            continue;
        }
        set_shadow(&tx, wire)?;
        match note(&tx, &wire.id) {
            Ok(mut n) => {
                let has_frozen: bool = tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM outbox WHERE note_id=?)",
                    [&wire.id],
                    |r| r.get(0),
                )?;
                if n.generation == n.acknowledged && !has_frozen {
                    let revision = counter(&wire.rev)?;
                    if n.base_rev == revision
                        && (n.content != wire.content || n.deleted != wire.deleted)
                    {
                        return Err(AppError::new(
                            "INVALID_REQUEST",
                            "同一版本笔记内容不一致，已保留本地数据",
                        ));
                    }
                    // An accepted push already installed this revision. Its
                    // eventual change-log replay only normalizes metadata;
                    // it must not invalidate the UI's acknowledged generation.
                    if n.base_rev != revision {
                        n.generation += 1;
                    }
                    n.content = wire.content.clone();
                    n.deleted = wire.deleted;
                    n.acknowledged = n.generation;
                    n.base_rev = revision;
                    n.created_at = wire.created_at.clone();
                    n.updated_at = wire.updated_at.clone();
                    n.conflict = wire.conflict.clone();
                    if n.purge_state == "pending" {
                        let expected: Option<i64> = tx
                            .query_row(
                                "SELECT base_rev FROM purge_requests WHERE note_id=?",
                                [&n.id],
                                |r| r.get(0),
                            )
                            .optional()?
                            .flatten();
                        if expected.is_some_and(|v| v != revision) {
                            crate::purge::reject(&tx, &n.id)?;
                            n.purge_state = "rejected".into();
                        }
                    }
                    put(&tx, &n)?;
                }
            }
            Err(e) if e.code == "NOT_FOUND" => {
                put(
                    &tx,
                    &LocalNote {
                        purge_state: "none".into(),
                        id: wire.id.clone(),
                        content: wire.content.clone(),
                        deleted: wire.deleted,
                        generation: 1,
                        acknowledged: 1,
                        base_rev: counter(&wire.rev)?,
                        created_at: wire.created_at.clone(),
                        updated_at: wire.updated_at.clone(),
                        conflict: wire.conflict.clone(),
                    },
                )?;
            }
            Err(e) => return Err(e),
        }
    }
    set_state(&tx, "cursor", cursor)?;
    set_state(&tx, "until", until)?;
    if fail_before_commit {
        return Err(AppError::new("LOCAL_STORAGE_FAILED", "注入的事务失败"));
    }
    tx.commit()?;
    Ok(())
}

pub(crate) fn acknowledge(
    db: &mut Connection,
    op: &Operation,
    generation: i64,
    result: &Value,
    device: &Profile,
) -> Result<()> {
    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let still_present: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE op_id=?)",
        [&op.op_id],
        |r| r.get(0),
    )?;
    if !still_present {
        return Ok(());
    }
    let mut n = note(&tx, &op.note_id)?;
    let status = result["status"].as_str().unwrap_or("error");
    if result["op_id"].as_str() != Some(&op.op_id) {
        return Err(AppError::new(
            "INVALID_REQUEST",
            "服务器确认操作无效，原待发送记录已保留",
        ));
    }
    if status == "error" {
        if result["error"]["code"].as_str().is_none()
            || result["error"]["retryable"].as_bool().is_none()
        {
            return Err(AppError::new("INVALID_REQUEST", "服务器错误确认结构无效"));
        }
        let retryable = result["error"]["retryable"].as_bool().unwrap_or(false);
        if !retryable {
            tx.execute(
                "UPDATE outbox SET blocked=?1 WHERE op_id=?2",
                params![result["error"].to_string(), op.op_id],
            )?;
            tx.commit()?;
        }
        return Err(AppError {
            code: result["error"]["code"]
                .as_str()
                .unwrap_or("INTERNAL_ERROR")
                .into(),
            message: "服务器未接受该笔记，本地内容已保留".into(),
            retryable,
        });
    }
    let revision = counter(
        result["rev"]
            .as_str()
            .ok_or_else(|| AppError::new("INVALID_REQUEST", "服务器确认缺少版本"))?,
    )?;
    if result["note_id"].as_str() != Some(&op.note_id) || revision < 1 {
        return Err(AppError::new("INVALID_REQUEST", "服务器确认笔记或版本无效"));
    }
    match status {
        "accepted" => {
            if revision
                != counter(&op.base_rev)?
                    .checked_add(1)
                    .ok_or_else(|| AppError::new("INVALID_REQUEST", "版本溢出"))?
                || counter(&text(result, "seq")?)? < 1
            {
                return Err(AppError::new("INVALID_REQUEST", "服务器接受版本无效"));
            }
        }
        "already_deleted" => {
            if !op.deleted
                || revision <= counter(&op.base_rev)?
                || counter(&text(result, "seq")?)? < 1
            {
                return Err(AppError::new("INVALID_REQUEST", "删除确认版本无效"));
            }
        }
        "conflict" => {
            if revision <= counter(&op.base_rev)?
                || counter(&text(result, "conflict_rev")?)? != 1
                || counter(&text(result, "conflict_seq")?)? < 1
            {
                return Err(AppError::new("INVALID_REQUEST", "冲突确认版本无效"));
            }
        }
        _ => return Err(AppError::new("INVALID_REQUEST", "服务器确认状态无效")),
    }
    match status {
        "accepted" | "already_deleted" => {
            let wire = WireNote {
                purged: false,
                id: op.note_id.clone(),
                content: op.content.clone(),
                deleted: op.deleted,
                rev: revision.to_string(),
                seq: text(result, "seq")?,
                created_at: n.created_at.clone(),
                updated_at: now(),
                conflict: n.conflict.clone(),
            };
            set_shadow(&tx, &wire)?;
            n.base_rev = revision;
            n.acknowledged = generation;
            if n.generation == generation
                && let Some(latest) = shadow(&tx, &n.id)?
            {
                n.content = latest.content;
                n.deleted = latest.deleted;
                n.base_rev = counter(&latest.rev)?;
                n.updated_at = latest.updated_at;
                n.created_at = latest.created_at;
                n.conflict = latest.conflict;
            }
            put(&tx, &n)?;
            if n.purge_state == "pending" && op.deleted && n.generation == generation {
                tx.execute(
                    "UPDATE purge_requests SET base_rev=?1 WHERE note_id=?2 AND base_rev IS NULL",
                    params![revision, n.id],
                )?;
            }
        }
        "conflict" => {
            if n.purge_state == "pending" {
                crate::purge::reject(&tx, &n.id)?;
                n.purge_state = "rejected".into();
            }
            let target = text(result, "conflict_note_id")?;
            if !valid_id(&target) || target == op.note_id {
                return Err(AppError::new("INVALID_REQUEST", "冲突副本标识无效"));
            }
            let conflict_rev = counter(&text(result, "conflict_rev")?)?;
            let latest = n.clone();
            let original = shadow(&tx, &op.note_id)?
                .ok_or_else(|| AppError::new("RECOVERY_REQUIRED", "缺少冲突原笔记的权威副本"))?;
            n.content = original.content;
            n.deleted = original.deleted;
            n.conflict = original.conflict;
            n.base_rev = counter(&original.rev)?;
            n.generation += 1;
            n.acknowledged = n.generation;
            n.updated_at = original.updated_at;
            put(&tx, &n)?;
            let meta = Conflict {
                source_note_id: op.note_id.clone(),
                source_device_id: device.device_id.clone().unwrap_or_default(),
                source_device_name: "本设备".into(),
                source_op_id: op.op_id.clone(),
                reason: if op.deleted {
                    "stale_delete"
                } else if n.deleted {
                    "edit_after_delete"
                } else {
                    "stale_edit"
                }
                .into(),
                detected_at: now(),
            };
            let wire = WireNote {
                purged: false,
                id: target.clone(),
                content: op.content.clone(),
                deleted: false,
                rev: conflict_rev.to_string(),
                seq: text(result, "conflict_seq")?,
                created_at: now(),
                updated_at: now(),
                conflict: Some(meta.clone()),
            };
            set_shadow(&tx, &wire)?;
            let newer = latest.generation > generation;
            let existing = match note(&tx, &target) {
                Ok(note) => Some(note),
                Err(error) if error.code == "NOT_FOUND" => None,
                Err(error) => return Err(error),
            };
            let existing_pending = if let Some(ref existing) = existing {
                existing.generation > existing.acknowledged
                    || tx.query_row(
                        "SELECT EXISTS(SELECT 1 FROM outbox WHERE note_id=?)",
                        [&target],
                        |r| r.get(0),
                    )?
            } else {
                false
            };
            let canonical = shadow(&tx, &target)?.unwrap_or(wire);
            let mut focus = target.clone();
            let target_generation;
            if existing_pending {
                // The conflict copy was already visible and independently
                // edited while the original result was unknown. Preserve both
                // local branches rather than overwrite either pending edit.
                target_generation = existing.as_ref().unwrap().generation;
                if newer {
                    focus = id();
                    put(
                        &tx,
                        &LocalNote {
                            purge_state: "none".into(),
                            id: focus.clone(),
                            content: latest.content.clone(),
                            deleted: latest.deleted,
                            generation: 1,
                            acknowledged: 0,
                            base_rev: 0,
                            created_at: now(),
                            updated_at: now(),
                            conflict: None,
                        },
                    )?;
                }
            } else if newer {
                target_generation = existing.as_ref().map_or(2, |n| n.generation + 1);
                put(
                    &tx,
                    &LocalNote {
                        purge_state: "none".into(),
                        id: target.clone(),
                        content: latest.content.clone(),
                        deleted: latest.deleted,
                        generation: target_generation,
                        acknowledged: target_generation - 1,
                        base_rev: conflict_rev,
                        created_at: canonical.created_at,
                        updated_at: now(),
                        conflict: canonical.conflict,
                    },
                )?;
            } else {
                target_generation = existing.as_ref().map_or(1, |n| n.generation + 1);
                put(
                    &tx,
                    &LocalNote {
                        purge_state: "none".into(),
                        id: target.clone(),
                        content: canonical.content,
                        deleted: canonical.deleted,
                        generation: target_generation,
                        acknowledged: target_generation,
                        base_rev: counter(&canonical.rev)?,
                        created_at: canonical.created_at,
                        updated_at: canonical.updated_at,
                        conflict: canonical.conflict,
                    },
                )?;
            }
            let focus_generation = if focus == target {
                target_generation
            } else {
                1
            };
            tx.execute("INSERT INTO redirects VALUES(?1,?2,?3,?4) ON CONFLICT(source_id) DO UPDATE SET target_id=excluded.target_id,source_generation=excluded.source_generation,target_generation=excluded.target_generation",params![op.note_id,focus,latest.generation,focus_generation])?;
        }
        _ => return Err(AppError::new("INVALID_REQUEST", "服务器确认状态无效")),
    }
    tx.execute("DELETE FROM outbox WHERE op_id=?", [&op.op_id])?;
    tx.commit()?;
    Ok(())
}

pub(crate) fn redirects(db: &Connection) -> Result<Value> {
    let mut statement = db.prepare("SELECT source_id,target_id FROM redirects")?;
    let entries = statement
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(Value::Object(
        entries.into_iter().map(|(k, v)| (k, json!(v))).collect(),
    ))
}

pub(crate) fn create(
    tx: &Transaction<'_>,
    content: String,
    request_id: &str,
    account: bool,
) -> Result<LocalNote> {
    if !valid_id(request_id) {
        return Err(AppError::new("INVALID_REQUEST", "创建请求标识无效"));
    }
    if let Some(previous) = tx
        .query_row(
            "SELECT note_id FROM local_requests WHERE request_id=?",
            [request_id],
            |r| r.get::<_, String>(0),
        )
        .optional()?
    {
        return note(tx, &previous);
    }
    if content.trim().is_empty() {
        return Err(AppError::new("INVALID_REQUEST", "空白草稿尚无需建立笔记"));
    }
    let stamp = now();
    let n = LocalNote {
        purge_state: "none".into(),
        id: id(),
        content,
        deleted: false,
        generation: 1,
        acknowledged: if account { 0 } else { 1 },
        base_rev: 0,
        created_at: stamp.clone(),
        updated_at: stamp,
        conflict: None,
    };
    put(tx, &n)?;
    tx.execute(
        "INSERT INTO local_requests VALUES(?1,?2)",
        params![request_id, n.id],
    )?;
    Ok(n)
}
