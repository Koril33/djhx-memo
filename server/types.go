// Package memo implements the authoritative v1 account and note synchronization service.
package memo

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const maxBody = 8 * 1024 * 1024
const maxContent = 1024 * 1024

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,31}$`)

// Conflict describes the immutable origin of a conflict copy.
type Conflict struct {
	SourceNoteID     string `json:"source_note_id"`
	SourceDeviceID   string `json:"source_device_id"`
	SourceDeviceName string `json:"source_device_name"`
	SourceOpID       string `json:"source_op_id"`
	Reason           string `json:"reason"`
	DetectedAt       string `json:"detected_at"`
}

// Note is the complete authoritative snapshot stored in the immutable change log.
type Note struct {
	ID        string    `json:"id"`
	Content   string    `json:"content"`
	Deleted   bool      `json:"deleted"`
	Rev       string    `json:"rev"`
	Seq       string    `json:"seq"`
	CreatedAt string    `json:"created_at"`
	UpdatedAt string    `json:"updated_at"`
	Conflict  *Conflict `json:"conflict"`
}

// Operation is a frozen, idempotent client write.
type Operation struct {
	OpID    string `json:"op_id"`
	NoteID  string `json:"note_id"`
	BaseRev string `json:"base_rev"`
	Content string `json:"content"`
	Deleted bool   `json:"deleted"`
}

// APIError is a stable, non-sensitive protocol error.
type APIError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

// Error returns only the stable code, never a request body or credential.
func (e *APIError) Error() string { return e.Code }
func apiError(code string) *APIError {
	return &APIError{Code: code, Message: code, Retryable: code == "SERVER_BUSY" || code == "INTERNAL_ERROR" || code == "RATE_LIMITED"}
}

// Result is one ordered push outcome. Fields depend on Status.
type Result struct {
	OpID           *string   `json:"op_id"`
	Status         string    `json:"status"`
	NoteID         string    `json:"note_id,omitempty"`
	Rev            string    `json:"rev,omitempty"`
	Seq            string    `json:"seq,omitempty"`
	ConflictNoteID string    `json:"conflict_note_id,omitempty"`
	ConflictRev    string    `json:"conflict_rev,omitempty"`
	ConflictSeq    string    `json:"conflict_seq,omitempty"`
	Error          *APIError `json:"error,omitempty"`
}

// Session is returned once when a device receives a new bearer credential.
type Session struct {
	UserID          string `json:"user_id"`
	Username        string `json:"username"`
	DeviceID        string `json:"device_id"`
	Token           string `json:"token"`
	ExpiresAt       string `json:"expires_at"`
	Epoch           string `json:"epoch"`
	ProtocolVersion int    `json:"protocol_version"`
}

// PullPage is a bounded page of immutable snapshots.
type PullPage struct {
	Epoch      string `json:"epoch"`
	Changes    []Note `json:"changes"`
	NextCursor string `json:"next_cursor"`
	Until      string `json:"until"`
	HasMore    bool   `json:"has_more"`
}

func uuid() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic("secure random unavailable")
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	s := hex.EncodeToString(b)
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}
func stamp(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }
func counter(s string) (int64, error) {
	if s == "" || len(s) > 19 || (len(s) > 1 && s[0] == '0') {
		return 0, apiError("INVALID_REQUEST")
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, apiError("INVALID_REQUEST")
		}
	}
	n, e := strconv.ParseInt(s, 10, 64)
	if e != nil {
		return 0, apiError("INVALID_REQUEST")
	}
	return n, nil
}
func num(n int64) string { return strconv.FormatInt(n, 10) }
func decode(raw []byte, dst any) error {
	if !utf8.Valid(raw) {
		return apiError("INVALID_REQUEST")
	}
	d := json.NewDecoder(strings.NewReader(string(raw)))
	d.DisallowUnknownFields()
	if e := d.Decode(dst); e != nil {
		return apiError("INVALID_REQUEST")
	}
	if e := d.Decode(new(any)); !errors.Is(e, io.EOF) {
		return apiError("INVALID_REQUEST")
	}
	return nil
}
func validateOp(raw []byte) (Operation, *string, *APIError) {
	var hint struct {
		OpID string `json:"op_id"`
	}
	_ = json.Unmarshal(raw, &hint)
	var id *string
	if uuidPattern.MatchString(hint.OpID) {
		id = &hint.OpID
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || len(fields) != 5 {
		return Operation{}, id, apiError("INVALID_REQUEST")
	}
	for _, k := range []string{"op_id", "note_id", "base_rev", "content", "deleted"} {
		if string(fields[k]) == "null" || len(fields[k]) == 0 {
			return Operation{}, id, apiError("INVALID_REQUEST")
		}
	}
	var o Operation
	if decode(raw, &o) != nil || !uuidPattern.MatchString(o.OpID) || !uuidPattern.MatchString(o.NoteID) {
		return o, id, apiError("INVALID_REQUEST")
	}
	if _, e := counter(o.BaseRev); e != nil {
		return o, id, apiError("INVALID_REQUEST")
	}
	if len(o.Content) > maxContent {
		return o, id, apiError("NOTE_TOO_LARGE")
	}
	if strings.ContainsRune(o.Content, '\r') {
		return o, id, apiError("INVALID_REQUEST")
	}
	return o, id, nil
}
