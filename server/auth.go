package memo

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

const passwordMemory = 19456

func randomToken() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic("secure random unavailable")
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func passwordHash(password string) string {
	salt := make([]byte, 16)
	if _, e := rand.Read(salt); e != nil {
		panic("secure random unavailable")
	}
	out := argon2.IDKey([]byte(password), salt, 2, passwordMemory, 1, 32)
	return fmt.Sprintf("$argon2id$v=19$m=%d,t=2,p=1$%s$%s", passwordMemory, base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(out))
}
func passwordOK(phc, password string) bool {
	p := strings.Split(phc, "$")
	if len(p) != 6 || p[1] != "argon2id" || p[2] != "v=19" {
		return false
	}
	var memory, iterations, threads uint32
	if n, e := fmt.Sscanf(p[3], "m=%d,t=%d,p=%d", &memory, &iterations, &threads); e != nil || n != 3 || memory < passwordMemory || memory > 1024*1024 || iterations < 2 || iterations > 20 || threads < 1 || threads > 16 {
		return false
	}
	salt, e := base64.RawStdEncoding.DecodeString(p[4])
	if e != nil || len(salt) < 16 {
		return false
	}
	want, e := base64.RawStdEncoding.DecodeString(p[5])
	if e != nil || len(want) < 32 || len(want) > 64 {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, iterations, memory, uint8(threads), uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}
func validPassword(password string) bool {
	return utf8.ValidString(password) && utf8.RuneCountInString(password) >= 12 && utf8.RuneCountInString(password) <= 128
}

type authRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	InviteCode string `json:"invite_code,omitempty"`
}

func validAuth(a authRequest, register bool) bool {
	return usernamePattern.MatchString(a.Username) && validPassword(a.Password) && uuidPattern.MatchString(a.DeviceID) && utf8.RuneCountInString(a.DeviceName) >= 1 && utf8.RuneCountInString(a.DeviceName) <= 80 && (!register || (len(a.InviteCode) > 0 && len(a.InviteCode) <= 256))
}

type identity struct{ UserID, DeviceID, Name, TokenHash string }
type querier interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func validIdentity(ctx context.Context, q querier, a identity, now string) error {
	var found int
	if e := q.QueryRowContext(ctx, "SELECT 1 FROM devices WHERE id=? AND user_id=? AND token_hash=? AND revoked_at IS NULL AND expires_at>?", a.DeviceID, a.UserID, a.TokenHash, now).Scan(&found); e != nil {
		if errors.Is(e, sql.ErrNoRows) {
			return apiError("AUTH_REQUIRED")
		}
		return e
	}
	return nil
}
func (s *Store) authenticate(ctx context.Context, token string) (identity, error) {
	var a identity
	if len(token) != 43 {
		return a, apiError("AUTH_REQUIRED")
	}
	a.TokenHash = hash(token)
	e := s.db.QueryRowContext(ctx, "SELECT user_id,id,name FROM devices WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?", a.TokenHash, stamp(s.now())).Scan(&a.UserID, &a.DeviceID, &a.Name)
	if errors.Is(e, sql.ErrNoRows) {
		return a, apiError("AUTH_REQUIRED")
	}
	if e != nil {
		return a, e
	}
	// Coarse last-seen updates avoid a durable write for every poll.
	_, e = s.db.ExecContext(ctx, "UPDATE devices SET last_seen_at=? WHERE id=? AND user_id=? AND token_hash=? AND revoked_at IS NULL AND (last_seen_at IS NULL OR last_seen_at<?)", stamp(s.now()), a.DeviceID, a.UserID, a.TokenHash, stamp(s.now().Add(-time.Minute)))
	return a, e
}
func (s *Store) register(ctx context.Context, a authRequest, epoch string) (Session, error) {
	var session Session
	phc := passwordHash(a.Password)
	token := randomToken()
	now := stamp(s.now())
	expires := stamp(s.now().Add(90 * 24 * time.Hour))
	userID := uuid()
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return session, e
	}
	defer func() { _ = tx.Rollback() }()
	var invitation string
	e = tx.QueryRowContext(ctx, "SELECT id FROM invitations WHERE code_hash=? AND used_by IS NULL AND revoked_at IS NULL AND expires_at>?", hash(a.InviteCode), now).Scan(&invitation)
	if errors.Is(e, sql.ErrNoRows) {
		return session, apiError("INVITE_INVALID")
	}
	if e != nil {
		return session, e
	}
	var count int
	if e = tx.QueryRowContext(ctx, "SELECT count(*) FROM users WHERE username=?", a.Username).Scan(&count); e != nil {
		return session, e
	}
	if count > 0 {
		return session, apiError("USERNAME_TAKEN")
	}
	if e = tx.QueryRowContext(ctx, "SELECT count(*) FROM devices WHERE id=?", a.DeviceID).Scan(&count); e != nil {
		return session, e
	}
	if count > 0 {
		return session, apiError("DEVICE_ID_UNAVAILABLE")
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO users VALUES(?,?,?,?)", userID, a.Username, phc, now); e != nil {
		return session, e
	}
	if _, e = tx.ExecContext(ctx, "UPDATE invitations SET used_by=?,used_at=? WHERE id=? AND used_by IS NULL", userID, now, invitation); e != nil {
		return session, e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO devices(id,user_id,name,token_hash,created_at,expires_at,last_seen_at) VALUES(?,?,?,?,?,?,?)", a.DeviceID, userID, a.DeviceName, hash(token), now, expires, now); e != nil {
		return session, e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO user_sync_state(user_id) VALUES(?)", userID); e != nil {
		return session, e
	}
	if s.failpoint != nil {
		if e = s.failpoint("register_before_commit"); e != nil {
			return session, e
		}
	}
	if e = tx.Commit(); e != nil {
		return session, e
	}
	return Session{userID, a.Username, a.DeviceID, token, expires, epoch, 1}, nil
}
func (s *Store) login(ctx context.Context, a authRequest, epoch, dummyHash string) (Session, error) {
	var session Session
	var userID, phc string
	e := s.db.QueryRowContext(ctx, "SELECT id,password_hash FROM users WHERE username=?", a.Username).Scan(&userID, &phc)
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return session, e
	}
	found := e == nil
	if !found {
		phc = dummyHash
	}
	ok := passwordOK(phc, a.Password)
	if !found || !ok {
		return session, apiError("AUTH_INVALID_CREDENTIALS")
	}
	token := randomToken()
	now := stamp(s.now())
	expires := stamp(s.now().Add(90 * 24 * time.Hour))
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return session, e
	}
	defer func() { _ = tx.Rollback() }()
	var latest string
	if e = tx.QueryRowContext(ctx, "SELECT password_hash FROM users WHERE id=?", userID).Scan(&latest); e != nil {
		return session, e
	}
	if latest != phc {
		return session, apiError("AUTH_INVALID_CREDENTIALS")
	}
	var owner string
	e = tx.QueryRowContext(ctx, "SELECT user_id FROM devices WHERE id=?", a.DeviceID).Scan(&owner)
	if e == nil && owner != userID {
		return session, apiError("DEVICE_ID_UNAVAILABLE")
	}
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return session, e
	}
	if _, e = tx.ExecContext(ctx, `INSERT INTO devices(id,user_id,name,token_hash,created_at,expires_at,last_seen_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,token_hash=excluded.token_hash,expires_at=excluded.expires_at,last_seen_at=excluded.last_seen_at,revoked_at=NULL`, a.DeviceID, userID, a.DeviceName, hash(token), now, expires, now); e != nil {
		return session, e
	}
	if e = tx.Commit(); e != nil {
		return session, e
	}
	return Session{userID, a.Username, a.DeviceID, token, expires, epoch, 1}, nil
}

// CreateInvitation returns an ID and a one-time random code; only its hash is stored.
func (s *Store) CreateInvitation(ctx context.Context, lifetime time.Duration) (string, string, error) {
	if lifetime <= 0 {
		return "", "", errors.New("invitation lifetime must be positive")
	}
	id := uuid()
	code := randomToken()
	_, e := s.db.ExecContext(ctx, "INSERT INTO invitations(id,code_hash,expires_at) VALUES(?,?,?)", id, hash(code), stamp(s.now().Add(lifetime)))
	return id, code, e
}

// RevokeInvitation invalidates an unused or previously issued invitation by ID.
func (s *Store) RevokeInvitation(ctx context.Context, id string) error {
	r, e := s.db.ExecContext(ctx, "UPDATE invitations SET revoked_at=? WHERE id=?", stamp(s.now()), id)
	if e != nil {
		return e
	}
	n, e := r.RowsAffected()
	if e == nil && n == 0 {
		return apiError("NOT_FOUND")
	}
	return e
}

// ResetPassword replaces a password and revokes every device atomically.
func (s *Store) ResetPassword(ctx context.Context, username, password string) error {
	if !validPassword(password) {
		return apiError("INVALID_REQUEST")
	}
	phc := passwordHash(password)
	tx, e := s.db.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer func() { _ = tx.Rollback() }()
	var id string
	if e = tx.QueryRowContext(ctx, "SELECT id FROM users WHERE username=?", username).Scan(&id); e != nil {
		if errors.Is(e, sql.ErrNoRows) {
			return apiError("NOT_FOUND")
		}
		return e
	}
	if _, e = tx.ExecContext(ctx, "UPDATE users SET password_hash=? WHERE id=?", phc, id); e != nil {
		return e
	}
	if _, e = tx.ExecContext(ctx, "UPDATE devices SET revoked_at=? WHERE user_id=?", stamp(s.now()), id); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Store) revoke(ctx context.Context, a identity, id string) error {
	r, e := s.db.ExecContext(ctx, "UPDATE devices SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=? AND id=?", stamp(s.now()), a.UserID, id)
	if e != nil {
		return e
	}
	n, e := r.RowsAffected()
	if e == nil && n == 0 {
		return apiError("NOT_FOUND")
	}
	return e
}
