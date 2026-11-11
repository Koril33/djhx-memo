//! Durable, profile-scoped permanent deletion. Ordinary writes remain in their original outbox.
use crate::{db, model::*};
use rusqlite::{Connection, OptionalExtension, TransactionBehavior, params};

pub(crate) fn request(
    db: &mut Connection,
    note_id: &str,
    generation: i64,
    account: bool,
) -> Result<LocalNote> {
    let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let mut n = db::note(&tx, note_id)?;
    if n.purge_state == "purged" {
        return Ok(n);
    }
    if n.generation != generation {
        return Err(AppError::new(
            "LOCAL_GENERATION_CONFLICT",
            "笔记已变化，请刷新后重试",
        ));
    }
    if !n.deleted {
        return Err(AppError::new(
            "PURGE_CONFLICT",
            "只能彻底删除回收站中的笔记",
        ));
    }
    if n.purge_state == "pending" {
        return Ok(n);
    }
    let frozen: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE note_id=?)",
        [note_id],
        |r| r.get(0),
    )?;
    if !account || (n.base_rev == 0 && !frozen && db::shadow(&tx, note_id)?.is_none()) {
        n.purge_state = "purged".into();
        n.content.clear();
        n.conflict = None;
        n.generation += 1;
        n.acknowledged = n.generation;
    } else {
        let base = if n.generation == n.acknowledged && !frozen {
            Some(n.base_rev)
        } else {
            None
        };
        tx.execute(
            "INSERT INTO purge_requests VALUES(?1,?2,?3)",
            params![note_id, id(), base],
        )?;
        n.purge_state = "pending".into();
    }
    db::put(&tx, &n)?;
    tx.commit()?;
    Ok(n)
}

pub(crate) fn next(db: &Connection) -> Result<Option<(String, String, i64)>> {
    Ok(db.query_row("SELECT p.note_id,p.op_id,p.base_rev FROM purge_requests p JOIN notes n ON n.id=p.note_id WHERE p.base_rev IS NOT NULL AND n.generation=n.acknowledged AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.note_id=n.id) ORDER BY p.rowid LIMIT 1", [], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).optional()?)
}

pub(crate) fn reject(db: &Connection, note_id: &str) -> Result<()> {
    db.execute("DELETE FROM purge_requests WHERE note_id=?", [note_id])?;
    db.execute(
        "UPDATE notes SET purge_state='rejected' WHERE id=? AND purge_state='pending'",
        [note_id],
    )?;
    Ok(())
}

/// Installs the irreversible state even when a stale edit or frozen write exists.
pub(crate) fn install(db: &Connection, wire: &WireNote) -> Result<()> {
    if !wire.purged
        || !wire.deleted
        || !wire.content.is_empty()
        || wire.conflict.is_some()
        || !valid_id(&wire.id)
        || counter(&wire.rev)? < 1
        || counter(&wire.seq)? < 1
    {
        return Err(AppError::new("INVALID_REQUEST", "永久删除快照无效"));
    }
    let generation = match db::note(db, &wire.id) {
        Ok(n) => n.generation + i64::from(n.purge_state != "purged"),
        Err(e) if e.code == "NOT_FOUND" => 1,
        Err(e) => return Err(e),
    };
    db::set_shadow(db, wire)?;
    db::put(
        db,
        &LocalNote {
            id: wire.id.clone(),
            content: String::new(),
            deleted: true,
            purge_state: "purged".into(),
            generation,
            acknowledged: generation,
            base_rev: counter(&wire.rev)?,
            created_at: wire.created_at.clone(),
            updated_at: wire.updated_at.clone(),
            conflict: None,
        },
    )?;
    db.execute("DELETE FROM outbox WHERE note_id=?", [&wire.id])?;
    db.execute("DELETE FROM purge_requests WHERE note_id=?", [&wire.id])?;
    db.execute("DELETE FROM redirects WHERE source_id=?", [&wire.id])?;
    Ok(())
}
