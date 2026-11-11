package memo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"net/http"
)

// PurgeRequest irreversibly removes one known trash version from the live product.
// Old change snapshots and backups are deliberately retained; this is not secure erasure.
type PurgeRequest struct {
	Epoch   string `json:"epoch"`
	OpID    string `json:"op_id"`
	NoteID  string `json:"note_id"`
	BaseRev string `json:"base_rev"`
}

func (s *Store) purge(ctx context.Context, a identity, p PurgeRequest) (Note, bool, error) {
	var n Note
	raw, _ := json.Marshal(p)
	fingerprint := hash("purge:" + string(raw))
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return n, false, e
	}
	defer func() { _ = tx.Rollback() }()
	if e = validIdentity(ctx, tx, a, stamp(s.now())); e != nil {
		return n, false, e
	}
	var savedHash, saved string
	e = tx.QueryRowContext(ctx, "SELECT fingerprint,result FROM processed_ops WHERE user_id=? AND op_id=?", a.UserID, p.OpID).Scan(&savedHash, &saved)
	if e == nil {
		if savedHash != fingerprint {
			return n, false, apiError("OP_ID_REUSED")
		}
		e = json.Unmarshal([]byte(saved), &n)
		return n, false, e
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return n, false, e
	}
	n, e = scanNote(tx.QueryRowContext(ctx, "SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", a.UserID, p.NoteID))
	if errors.Is(e, sql.ErrNoRows) {
		return n, false, apiError("NOTE_NOT_FOUND")
	}
	if e != nil {
		return n, false, e
	}
	changed := !n.Purged
	if changed {
		if !n.Deleted || n.Rev != p.BaseRev {
			return n, false, apiError("PURGE_CONFLICT")
		}
		current, _ := counter(n.Rev)
		var head int64
		if e = tx.QueryRowContext(ctx, "SELECT head_seq FROM user_sync_state WHERE user_id=?", a.UserID).Scan(&head); e != nil {
			return n, false, e
		}
		if current == math.MaxInt64 || head == math.MaxInt64 {
			return n, false, apiError("SERVER_BUSY")
		}
		n.Content = ""
		n.Deleted = true
		n.Purged = true
		n.Conflict = nil
		n.Rev = num(current + 1)
		n.Seq = num(head + 1)
		n.UpdatedAt = stamp(s.now())
		if _, e = tx.ExecContext(ctx, "UPDATE notes SET content='',deleted=1,purged=1,conflict=NULL,rev=?,seq=?,updated_at=? WHERE user_id=? AND id=?", n.Rev, n.Seq, n.UpdatedAt, a.UserID, n.ID); e != nil {
			return n, false, e
		}
		if s.failpoint != nil {
			if e = s.failpoint("after_note"); e != nil {
				return n, false, e
			}
		}
		snapshot, _ := json.Marshal(n)
		if _, e = tx.ExecContext(ctx, "INSERT INTO changes VALUES(?,?,?)", a.UserID, n.Seq, string(snapshot)); e != nil {
			return n, false, e
		}
		if _, e = tx.ExecContext(ctx, "UPDATE user_sync_state SET head_seq=? WHERE user_id=?", n.Seq, a.UserID); e != nil {
			return n, false, e
		}
	}
	result, _ := json.Marshal(n)
	if _, e = tx.ExecContext(ctx, "INSERT INTO processed_ops VALUES(?,?,?,?,?)", a.UserID, p.OpID, fingerprint, string(result), stamp(s.now())); e != nil {
		return n, false, e
	}
	if s.failpoint != nil {
		if e = s.failpoint("before_commit"); e != nil {
			return n, false, e
		}
	}
	if e = tx.Commit(); e != nil {
		return n, false, e
	}
	return n, changed, nil
}

func (s *Server) purgeHTTP(w http.ResponseWriter, r *http.Request, a identity) {
	var p PurgeRequest
	if e := readJSON(w, r, 4096, &p); e != nil {
		writeError(w, r, e)
		return
	}
	base, e := counter(p.BaseRev)
	if e != nil || base < 1 || !uuidPattern.MatchString(p.OpID) || !uuidPattern.MatchString(p.NoteID) || !uuidPattern.MatchString(p.Epoch) {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if p.Epoch != s.cfg.Epoch {
		writeError(w, r, apiError("SYNC_EPOCH_MISMATCH"))
		return
	}
	n, changed, e := s.store.purge(r.Context(), a, p)
	if e != nil {
		writeError(w, r, internal(e))
		return
	}
	if changed {
		s.notify(a.UserID)
	}
	writeJSON(w, 200, map[string]any{"epoch": s.cfg.Epoch, "note": n})
}
