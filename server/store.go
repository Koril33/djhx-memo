package memo

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Store owns one serialized SQLite connection with durable WAL commits.
type Store struct {
	db        *sql.DB
	now       func() time.Time
	failpoint func(string) error
}

// Open initializes or validates versioned migrations. Existing migration hashes must match.
func Open(path string) (*Store, error) {
	if path == "" {
		return nil, errors.New("database path required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	// Pre-create with private permissions; SQLite-created WAL/SHM inherit database mode.
	f, e := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = f.Close(); e != nil {
		return nil, e
	}
	if e = os.Chmod(path, 0600); e != nil {
		return nil, e
	}
	abs, e := filepath.Abs(path)
	if e != nil {
		return nil, e
	}
	query := url.Values{"_pragma": {"busy_timeout(5000)", "foreign_keys(1)", "journal_mode(WAL)", "synchronous(FULL)"}}
	// A slash before a Windows drive keeps it a file URI path, rather than an authority.
	dsn := (&url.URL{Scheme: "file", Path: "/" + strings.TrimPrefix(filepath.ToSlash(abs), "/"), RawQuery: query.Encode()}).String()
	db, e := sql.Open("sqlite", dsn)
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	s := &Store{db: db, now: time.Now}
	if e = s.initialize(); e != nil {
		_ = db.Close()
		return nil, e
	}
	return s, nil
}
func (s *Store) initialize() error {
	for _, q := range []string{"PRAGMA busy_timeout=5000", "PRAGMA foreign_keys=ON", "PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL"} {
		if _, e := s.db.Exec(q); e != nil {
			return e
		}
	}
	var version int
	if e := s.db.QueryRow("PRAGMA user_version").Scan(&version); e != nil {
		return e
	}
	names := []string{"migrations/001_initial.sql", "migrations/002_permanent_deletion.sql"}
	if version > len(names) {
		return errors.New("database schema is newer than this server")
	}
	if _, e := s.db.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)`); e != nil {
		return e
	}
	var latest int
	if e := s.db.QueryRow("SELECT COALESCE(MAX(version),0) FROM schema_migrations").Scan(&latest); e != nil {
		return e
	}
	if latest != version {
		return errors.New("inconsistent or unsupported migration version")
	}
	for i, name := range names {
		raw, e := migrations.ReadFile(name)
		if e != nil {
			return e
		}
		sum := hash(string(raw))
		next := i + 1
		if next <= version {
			var saved string
			if e = s.db.QueryRow("SELECT checksum FROM schema_migrations WHERE version=?", next).Scan(&saved); e != nil {
				return e
			}
			if saved != sum {
				return errors.New("migration checksum mismatch")
			}
			continue
		}
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		if _, e = tx.Exec(string(raw)); e == nil {
			_, e = tx.Exec("INSERT INTO schema_migrations VALUES(?,?,?)", next, sum, stamp(s.now()))
		}
		if e == nil {
			_, e = tx.Exec(fmt.Sprintf("PRAGMA user_version=%d", next))
		}
		if e != nil {
			_ = tx.Rollback()
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
	}
	return nil
}

// Close releases the database after active requests have stopped.
func (s *Store) Close() error { return s.db.Close() }

// Healthy checks the active database connection.
func (s *Store) Healthy(ctx context.Context) error {
	var count int
	if e := s.db.QueryRowContext(ctx, "SELECT count(*) FROM schema_migrations WHERE version=1").Scan(&count); e != nil {
		return e
	}
	if count != 1 {
		return errors.New("migration metadata unavailable")
	}
	return nil
}
func hash(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }
func (s *Store) head(ctx context.Context, user string) (int64, error) {
	var h int64
	e := s.db.QueryRowContext(ctx, "SELECT head_seq FROM user_sync_state WHERE user_id=?", user).Scan(&h)
	return h, e
}
func scanNote(row interface{ Scan(...any) error }) (Note, error) {
	var n Note
	var rev, seq int64
	var conflict sql.NullString
	e := row.Scan(&n.ID, &n.Content, &n.Deleted, &rev, &seq, &n.CreatedAt, &n.UpdatedAt, &conflict, &n.Purged)
	if e != nil {
		return n, e
	}
	n.Rev = num(rev)
	n.Seq = num(seq)
	if conflict.Valid {
		if e = json.Unmarshal([]byte(conflict.String), &n.Conflict); e != nil {
			return n, e
		}
	}
	return n, nil
}

const noteColumns = "id,content,deleted,rev,seq,created_at,updated_at,conflict,purged"

func internal(err error) *APIError {
	if err == nil {
		return nil
	}
	var a *APIError
	if errors.As(err, &a) {
		return a
	}
	if strings.Contains(err.Error(), "SQLITE_BUSY") || strings.Contains(err.Error(), "locked") {
		return apiError("SERVER_BUSY")
	}
	return apiError("INTERNAL_ERROR")
}
func resultErr(id *string, err *APIError) Result {
	return Result{OpID: id, Status: "error", Error: err}
}
func (s *Store) push(ctx context.Context, a identity, o Operation) (Result, bool, error) {
	id := o.OpID
	result := Result{OpID: &id}
	raw, _ := json.Marshal(o)
	fingerprint := hash(string(raw))
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return result, false, e
	}
	defer func() { _ = tx.Rollback() }()
	// Revalidate inside each write transaction so revocation cannot race a batch.
	if e = validIdentity(ctx, tx, a, stamp(s.now())); e != nil {
		return result, false, e
	}
	var oldFingerprint, oldResult string
	e = tx.QueryRowContext(ctx, "SELECT fingerprint,result FROM processed_ops WHERE user_id=? AND op_id=?", a.UserID, o.OpID).Scan(&oldFingerprint, &oldResult)
	if e == nil {
		if oldFingerprint != fingerprint {
			return resultErr(&id, apiError("OP_ID_REUSED")), false, nil
		}
		e = json.Unmarshal([]byte(oldResult), &result)
		return result, false, e
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return result, false, e
	}
	n, e := scanNote(tx.QueryRowContext(ctx, "SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", a.UserID, o.NoteID))
	exists := e == nil
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return result, false, e
	}
	base, _ := counter(o.BaseRev)
	if exists && n.Purged {
		return resultErr(&id, apiError("NOTE_PURGED")), false, nil
	}
	current, _ := counter(n.Rev)
	if !exists && base > 0 {
		return resultErr(&id, apiError("NOTE_NOT_FOUND")), false, nil
	}
	if exists && base > current {
		return resultErr(&id, apiError("INVALID_BASE_REV")), false, nil
	}
	now := stamp(s.now())
	changed := true
	if exists && base < current && n.Deleted && o.Deleted && n.Content == o.Content {
		result.Status = "already_deleted"
		result.NoteID = n.ID
		result.Rev = n.Rev
		result.Seq = n.Seq
		changed = false
	} else {
		var head int64
		if e = tx.QueryRowContext(ctx, "SELECT head_seq FROM user_sync_state WHERE user_id=?", a.UserID).Scan(&head); e != nil {
			return result, false, e
		}
		if head == math.MaxInt64 {
			return result, false, apiError("SERVER_BUSY")
		}
		head++
		if !exists {
			n = Note{ID: o.NoteID, Content: o.Content, Deleted: o.Deleted, Rev: "1", Seq: num(head), CreatedAt: now, UpdatedAt: now}
			result.Status = "accepted"
			result.NoteID = n.ID
			result.Rev = n.Rev
			result.Seq = n.Seq
		} else if base == current {
			if current == math.MaxInt64 {
				return result, false, apiError("SERVER_BUSY")
			}
			n.Content = o.Content
			n.Deleted = o.Deleted
			n.Rev = num(current + 1)
			n.Seq = num(head)
			n.UpdatedAt = now
			result.Status = "accepted"
			result.NoteID = n.ID
			result.Rev = n.Rev
			result.Seq = n.Seq
		} else {
			reason := "stale_edit"
			if o.Deleted {
				reason = "stale_delete"
			} else if n.Deleted {
				reason = "edit_after_delete"
			}
			result.Status = "conflict"
			result.NoteID = n.ID
			result.Rev = n.Rev
			n = Note{ID: uuid(), Content: o.Content, Deleted: false, Rev: "1", Seq: num(head), CreatedAt: now, UpdatedAt: now, Conflict: &Conflict{SourceNoteID: o.NoteID, SourceDeviceID: a.DeviceID, SourceDeviceName: a.Name, SourceOpID: o.OpID, Reason: reason, DetectedAt: now}}
			result.ConflictNoteID = n.ID
			result.ConflictRev = n.Rev
			result.ConflictSeq = n.Seq
		}
		var conflict any
		if n.Conflict != nil {
			v, _ := json.Marshal(n.Conflict)
			conflict = string(v)
		}
		if _, e = tx.ExecContext(ctx, `INSERT INTO notes(user_id,id,content,deleted,rev,seq,created_at,updated_at,conflict) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET content=excluded.content,deleted=excluded.deleted,rev=excluded.rev,seq=excluded.seq,updated_at=excluded.updated_at,conflict=excluded.conflict`, a.UserID, n.ID, n.Content, n.Deleted, n.Rev, n.Seq, n.CreatedAt, n.UpdatedAt, conflict); e != nil {
			return result, false, e
		}
		if s.failpoint != nil {
			if e = s.failpoint("after_note"); e != nil {
				return result, false, e
			}
		}
		snapshot, _ := json.Marshal(n)
		if _, e = tx.ExecContext(ctx, "INSERT INTO changes(user_id,seq,snapshot) VALUES(?,?,?)", a.UserID, head, string(snapshot)); e != nil {
			return result, false, e
		}
		if _, e = tx.ExecContext(ctx, "UPDATE user_sync_state SET head_seq=? WHERE user_id=?", head, a.UserID); e != nil {
			return result, false, e
		}
	}
	out, _ := json.Marshal(result)
	if _, e = tx.ExecContext(ctx, "INSERT INTO processed_ops VALUES(?,?,?,?,?)", a.UserID, o.OpID, fingerprint, string(out), now); e != nil {
		return result, false, e
	}
	if s.failpoint != nil {
		if e = s.failpoint("before_commit"); e != nil {
			return result, false, e
		}
	}
	if e = tx.Commit(); e != nil {
		return result, false, e
	}
	return result, changed, nil
}
func (s *Store) pull(ctx context.Context, user, epoch string, cursor, until int64, limit int) (PullPage, error) {
	p := PullPage{Epoch: epoch, Changes: []Note{}, NextCursor: num(cursor)}
	tx, e := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if e != nil {
		return p, e
	}
	defer func() { _ = tx.Rollback() }()
	var head int64
	if e = tx.QueryRowContext(ctx, "SELECT head_seq FROM user_sync_state WHERE user_id=?", user).Scan(&head); e != nil {
		return p, e
	}
	if until < 0 {
		until = head
	}
	if cursor > head || until > head || until < cursor {
		return p, apiError("CURSOR_INVALID")
	}
	p.Until = num(until)
	rows, e := tx.QueryContext(ctx, "SELECT snapshot FROM changes WHERE user_id=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?", user, cursor, until, limit)
	if e != nil {
		return p, e
	}
	// Reserve envelope bytes and account for JSON escaping using actual wire snapshots.
	used := 512
	for rows.Next() {
		var raw string
		if e = rows.Scan(&raw); e != nil {
			_ = rows.Close()
			return p, e
		}
		if used+len(raw)+1 > maxBody {
			break
		}
		var n Note
		if e = json.Unmarshal([]byte(raw), &n); e != nil {
			_ = rows.Close()
			return p, e
		}
		p.Changes = append(p.Changes, n)
		p.NextCursor = n.Seq
		used += len(raw) + 1
	}
	e = rows.Err()
	_ = rows.Close()
	if e != nil {
		return p, e
	}
	next, _ := counter(p.NextCursor)
	p.HasMore = next < until
	if !p.HasMore {
		p.NextCursor = num(until)
	}
	return p, tx.Commit()
}

// Backup creates a new consistent SQLite snapshot without stopping the writer.
// Destination must not already exist; callers should encrypt and copy it off-host.
func (s *Store) Backup(ctx context.Context, destination string) error {
	abs, e := filepath.Abs(destination)
	if e != nil {
		return e
	}
	if _, e = os.Stat(abs); !errors.Is(e, os.ErrNotExist) {
		return errors.New("backup destination must not exist")
	}
	if e = os.MkdirAll(filepath.Dir(abs), 0700); e != nil {
		return e
	}
	reserved, e := os.OpenFile(abs, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0600)
	if e != nil {
		return e
	}
	if e = reserved.Close(); e != nil {
		_ = os.Remove(abs)
		return e
	}
	if _, e = s.db.ExecContext(ctx, "VACUUM INTO ?", abs); e != nil {
		_ = os.Remove(abs)
		return e
	}
	return os.Chmod(abs, 0600)
}

// IntegrityCheck validates a restored snapshot before opening it for writes.
func (s *Store) IntegrityCheck(ctx context.Context) error {
	var status string
	if e := s.db.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&status); e != nil {
		return e
	}
	if status != "ok" {
		return fmt.Errorf("database integrity check failed")
	}
	return nil
}
