package memo

import (
	"archive/zip"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func purgeFixture(t *testing.T) (*Store, identity, string) {
	t.Helper()
	s, e := Open(filepath.Join(t.TempDir(), "memo.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = s.Close() })
	epoch := uuid()
	_, invite, e := s.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	session, e := s.register(context.Background(), authRequest{Username: "purgeuser", Password: "a long test password", DeviceID: uuid(), DeviceName: "test", InviteCode: invite}, epoch)
	if e != nil {
		t.Fatal(e)
	}
	a, e := s.authenticate(context.Background(), session.Token)
	if e != nil {
		t.Fatal(e)
	}
	return s, a, epoch
}

func TestPermanentDeletionConformance(t *testing.T) {
	s, a, epoch := purgeFixture(t)
	ctx := context.Background()
	noteID := uuid()
	push := func(base, body string, deleted bool) Result {
		r, _, e := s.push(ctx, a, Operation{uuid(), noteID, base, body, deleted})
		if e != nil {
			t.Fatal(e)
		}
		return r
	}
	if r := push("0", "fixture", false); r.Status != "accepted" {
		t.Fatal(r)
	}
	p := PurgeRequest{epoch, uuid(), noteID, "1"}
	if _, _, e := s.purge(ctx, a, p); e == nil || e.Error() != "PURGE_CONFLICT" {
		t.Fatal("active note accepted", e)
	}
	push("1", "fixture", true)
	p.BaseRev = "2"
	_, invite, e := s.CreateInvitation(ctx, time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	otherSession, e := s.register(ctx, authRequest{Username: "otherpurge", Password: "a long test password", DeviceID: uuid(), DeviceName: "other", InviteCode: invite}, epoch)
	if e != nil {
		t.Fatal(e)
	}
	other, e := s.authenticate(ctx, otherSession.Token)
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e := s.purge(ctx, other, p); e == nil || e.Error() != "NOTE_NOT_FOUND" {
		t.Fatal("cross-account purge", e)
	}
	s.failpoint = func(string) error { return errors.New("injected") }
	if _, _, e := s.purge(ctx, a, p); e == nil {
		t.Fatal("missing rollback")
	}
	s.failpoint = nil
	n, changed, e := s.purge(ctx, a, p)
	if e != nil || !changed || !n.Purged || n.Content != "" || n.Rev != "3" {
		t.Fatal(n, e)
	}
	again, changed, e := s.purge(ctx, a, p)
	if e != nil || changed || again.Rev != n.Rev {
		t.Fatal("lost reply retry", again, e)
	}
	reused := p
	reused.BaseRev = "1"
	if _, _, e = s.purge(ctx, a, reused); e == nil || e.Error() != "OP_ID_REUSED" {
		t.Fatal(e)
	}
	for _, base := range []string{"0", "1", "3"} {
		if r := push(base, "old device edit", false); r.Error == nil || r.Error.Code != "NOTE_PURGED" {
			t.Fatal("resurrected", r)
		}
	}
	page, e := s.pull(ctx, a.UserID, epoch, 0, -1, 100)
	if e != nil || len(page.Changes) != 3 || !page.Changes[2].Purged {
		t.Fatal(page, e)
	}
	noteID = uuid()
	push("0", "restore fixture", true)
	p = PurgeRequest{epoch, uuid(), noteID, "1"}
	push("1", "restored", false)
	if _, _, e = s.purge(ctx, a, p); e == nil || e.Error() != "PURGE_CONFLICT" {
		t.Fatal("restored note lost", e)
	}
	api, e := New(s, Config{Epoch: epoch, DevHTTP: true})
	if e != nil {
		t.Fatal(e)
	}
	req := httptest.NewRequest(http.MethodPost, "/v1/notes/purge", nil)
	// Authenticated legacy writers must be refused before decoding an operation.
	session, e := s.login(ctx, authRequest{Username: "purgeuser", Password: "a long test password", DeviceID: uuid(), DeviceName: "legacy"}, epoch, "")
	if e != nil {
		t.Fatal(e)
	}
	req.Header.Set("Authorization", "Bearer "+session.Token)
	response := httptest.NewRecorder()
	api.Handler().ServeHTTP(response, req)
	if response.Code != 426 {
		t.Fatal("legacy client not blocked", response.Code)
	}
	checkHTTP := func(request PurgeRequest, want int) {
		t.Helper()
		body, _ := json.Marshal(request)
		r := httptest.NewRequest(http.MethodPost, "/v1/notes/purge", bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+session.Token)
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("X-Memo-Protocol", "2")
		w := httptest.NewRecorder()
		api.Handler().ServeHTTP(w, r)
		if w.Code != want {
			t.Fatalf("purge HTTP status %d, want %d", w.Code, want)
		}
	}
	checkHTTP(PurgeRequest{uuid(), uuid(), n.ID, "3"}, 409)
	checkHTTP(PurgeRequest{epoch, uuid(), noteID, "1"}, 409)
	checkHTTP(PurgeRequest{epoch, uuid(), uuid(), "1"}, 422)
	checkHTTP(PurgeRequest{epoch, uuid(), n.ID, "3"}, 200)
	if e = s.revoke(ctx, a, session.DeviceID); e != nil {
		t.Fatal(e)
	}
	checkHTTP(PurgeRequest{epoch, uuid(), n.ID, "3"}, 401)
	// A fresh export session remains independent of the revoked writer.
	session, e = s.login(ctx, authRequest{Username: "purgeuser", Password: "a long test password", DeviceID: uuid(), DeviceName: "export"}, epoch, "")
	if e != nil {
		t.Fatal(e)
	}
	request := httptest.NewRequest(http.MethodGet, "/v1/export?include_deleted=true", nil)
	request.Header.Set("Authorization", "Bearer "+session.Token)
	request.Header.Set("X-Memo-Protocol", "2")
	response = httptest.NewRecorder()
	api.Handler().ServeHTTP(response, request)
	if response.Code != 200 {
		t.Fatal("export failed", response.Code)
	}
	archive, e := zip.NewReader(bytes.NewReader(response.Body.Bytes()), int64(response.Body.Len()))
	if e != nil {
		t.Fatal(e)
	}
	for _, f := range archive.File {
		if f.Name == n.ID+".md" {
			t.Fatal("purged content exported")
		}
	}
}

func TestPermanentDeletionMigrationFromBaseline(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	db, e := sql.Open("sqlite", path)
	if e != nil {
		t.Fatal(e)
	}
	raw, e := migrations.ReadFile("migrations/001_initial.sql")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(string(raw)); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)"); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("INSERT INTO schema_migrations VALUES(1,?,?)", hash(string(raw)), stamp(time.Now())); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("PRAGMA user_version=1"); e != nil {
		t.Fatal(e)
	}
	userID, noteID := uuid(), uuid()
	if _, e = db.Exec("INSERT INTO users VALUES(?,?,?,?)", userID, "migration_fixture", "unused-test-hash", stamp(time.Now())); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,NULL)", userID, noteID, "preserved trash", true, 7, 11, stamp(time.Now()), stamp(time.Now())); e != nil {
		t.Fatal(e)
	}
	if e = db.Close(); e != nil {
		t.Fatal(e)
	}
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	var version int
	if e = s.db.QueryRow("PRAGMA user_version").Scan(&version); e != nil || version != 2 {
		t.Fatal(version, e)
	}
	legacy, e := scanNote(s.db.QueryRow("SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", userID, noteID))
	if e != nil || legacy.Content != "preserved trash" || !legacy.Deleted || legacy.Purged || legacy.Rev != "7" {
		t.Fatal("upgrade did not preserve existing note", e)
	}
	if _, e = s.db.Exec("PRAGMA user_version=3"); e != nil {
		t.Fatal(e)
	}
	if e = s.Close(); e != nil {
		t.Fatal(e)
	}
	if s, e = Open(path); e == nil {
		if e = s.Close(); e != nil {
			t.Fatal(e)
		}
		t.Fatal("newer schema opened")
	}
}

func TestPermanentDeletionBackupRestore(t *testing.T) {
	s, a, epoch := purgeFixture(t)
	ctx := context.Background()
	noteID := uuid()
	if r, _, e := s.push(ctx, a, Operation{uuid(), noteID, "0", "backup fixture", true}); e != nil || r.Status != "accepted" {
		t.Fatal("create trash", r, e)
	}
	p := PurgeRequest{epoch, uuid(), noteID, "1"}
	n, changed, e := s.purge(ctx, a, p)
	if e != nil || !changed || !n.Purged {
		t.Fatal("purge before backup", n, changed, e)
	}
	path := filepath.Join(t.TempDir(), "restored.db")
	if e = s.Backup(ctx, path); e != nil {
		t.Fatal(e)
	}
	restored, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = restored.Close() })
	marker, e := scanNote(restored.db.QueryRowContext(ctx, "SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", a.UserID, noteID))
	if e != nil || !marker.Purged || !marker.Deleted || marker.Content != "" || marker.Rev != n.Rev || marker.Seq != n.Seq {
		t.Fatal("backup lost permanent marker", marker, e)
	}
	replayed, changed, e := restored.purge(ctx, a, p)
	if e != nil || changed || replayed.Rev != n.Rev || replayed.Seq != n.Seq || !replayed.Purged {
		t.Fatal("backup lost idempotent result", replayed, changed, e)
	}
	r, changed, e := restored.push(ctx, a, Operation{uuid(), noteID, n.Rev, "stale restore", false})
	if e != nil || changed || r.Error == nil || r.Error.Code != "NOTE_PURGED" {
		t.Fatal("restored marker allowed resurrection", r, changed, e)
	}
	head, e := restored.head(ctx, a.UserID)
	if e != nil || num(head) != n.Seq {
		t.Fatal("restore retry advanced sequence", head, e)
	}
}

// TestPermanentDeletionCoreHTTP runs the real Rust client against this Go handler on Windows too.
// Explicit source-file testing excludes the Unix-only epoch/CLI; it does not substitute a mock server.
func TestPermanentDeletionCoreHTTP(t *testing.T) {
	if os.Getenv("DJHX_CORE_PURGE_INTEGRATION") != "1" {
		t.Skip("set DJHX_CORE_PURGE_INTEGRATION=1 for Rust/Go integration")
	}
	s, _, epoch := purgeFixture(t)
	api, e := New(s, Config{Epoch: epoch, DevHTTP: true, RegisterAttempts: 100, LoginFailures: 100})
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewServer(api.Handler())
	defer ts.Close()
	defer func() { _ = api.Shutdown(context.Background()) }()
	_, invite, e := s.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	cmd := exec.Command("cargo", "test", "--locked", "--manifest-path", "../core/Cargo.toml", "--test", "server_integration", "permanent_delete_http_conformance", "--", "--ignored", "--nocapture")
	// Windows Go -race may use MinGW through CC; Cargo's MSVC target must choose its own compiler.
	for _, setting := range os.Environ() {
		if runtime.GOOS != "windows" || !strings.HasPrefix(setting, "CC=") {
			cmd.Env = append(cmd.Env, setting)
		}
	}
	cmd.Env = append(cmd.Env, "DJHX_PURGE_TEST_URL="+ts.URL, "DJHX_PURGE_TEST_INVITE="+invite)
	output, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("Rust/Go integration: %v\n%s", e, output)
	}
	t.Log(string(output))
}

func TestPermanentDeletionAndroidHTTP(t *testing.T) {
	serial := os.Getenv("DJHX_ANDROID_TEST_SERIAL")
	if serial == "" {
		t.Skip("set DJHX_ANDROID_TEST_SERIAL for the isolated validation APK")
	}
	s, _, epoch := purgeFixture(t)
	api, e := New(s, Config{Epoch: epoch, DevHTTP: true, RegisterAttempts: 100, LoginFailures: 100})
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewServer(api.Handler())
	defer ts.Close()
	defer func() { _ = api.Shutdown(context.Background()) }()
	_, invite, e := s.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	// Android Emulator maps 10.0.2.2 to this host's loopback. Only synthetic test accounts are used.
	url := strings.Replace(ts.URL, "127.0.0.1", "10.0.2.2", 1)
	cmd := exec.Command("adb", "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", "app.djhx.memo.PermanentDeletionTest#accountPermanentDeletionConvergesThroughRealHTTP", "-e", "serverUrl", url, "-e", "inviteCode", invite, "app.djhx.memo.validation014.test/androidx.test.runner.AndroidJUnitRunner")
	output, e := cmd.CombinedOutput()
	if e != nil || !strings.Contains(string(output), "OK (1 test)") {
		t.Fatalf("Android/Go integration failed: %v\n%s", e, output)
	}
	t.Log("PASS Android JNI/SQLite + real Go HTTP: account permanent deletion and stale edit convergence")
}
