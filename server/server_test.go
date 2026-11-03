package memo

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

type testEnv struct {
	store *Store
	api   *Server
	http  *httptest.Server
	epoch string
}

func setup(t *testing.T) *testEnv {
	t.Helper()
	s, e := Open(filepath.Join(t.TempDir(), "memo.db"))
	if e != nil {
		t.Fatal(e)
	}
	epoch := uuid()
	api, e := New(s, Config{Epoch: epoch, DevHTTP: true, RegisterAttempts: 100, LoginFailures: 5, Heartbeat: 10 * time.Millisecond})
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewServer(api.Handler())
	t.Cleanup(func() { _ = api.Shutdown(context.Background()); ts.Close(); _ = s.Close() })
	return &testEnv{s, api, ts, epoch}
}
func (t *testEnv) request(method, path, token string, body any) (int, []byte, http.Header) {
	var b io.Reader
	if body != nil {
		if raw, ok := body.([]byte); ok {
			b = bytes.NewReader(raw)
		} else {
			raw, _ := json.Marshal(body)
			b = bytes.NewReader(raw)
		}
	}
	r, _ := http.NewRequest(method, t.http.URL+path, b)
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	resp, e := http.DefaultClient.Do(r)
	if e != nil {
		panic(e)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, raw, resp.Header
}
func (t *testEnv) account(test *testing.T, name string) Session {
	test.Helper()
	_, code, e := t.store.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		test.Fatal(e)
	}
	status, raw, _ := t.request("POST", "/v1/auth/register", "", map[string]string{"username": name, "password": "correct long password", "device_id": uuid(), "device_name": "设备 A", "invite_code": code})
	if status != 201 {
		test.Fatalf("register %d: %s", status, raw)
	}
	var session Session
	if e = json.Unmarshal(raw, &session); e != nil {
		test.Fatal(e)
	}
	return session
}
func (t *testEnv) identity(test *testing.T, session Session) identity {
	test.Helper()
	a, e := t.store.authenticate(context.Background(), session.Token)
	if e != nil {
		test.Fatal(e)
	}
	return a
}
func (t *testEnv) push(test *testing.T, session Session, ops ...Operation) []Result {
	test.Helper()
	status, raw, _ := t.request("POST", "/v1/sync/push", session.Token, map[string]any{"epoch": t.epoch, "ops": ops})
	if status != 200 {
		test.Fatalf("push %d: %s", status, raw)
	}
	var p struct {
		Results []Result `json:"results"`
	}
	if e := json.Unmarshal(raw, &p); e != nil {
		test.Fatal(e)
	}
	return p.Results
}

func TestSharedConformance(t *testing.T) {
	raw, e := os.ReadFile("../spec/conformance/fixtures.json")
	if e != nil {
		t.Fatal(e)
	}
	type entity struct {
		Note, Content, Rev, Seq string
		Deleted                 bool
	}
	var suite struct {
		SchemaVersion int `json:"schema_version"`
		Cases         []struct {
			CaseID  string `json:"case_id"`
			Initial []entity
			Ops     []struct {
				Op, Device, Note, Content string
				BaseRev                   string `json:"base_rev"`
				Deleted                   bool
				Expect                    struct {
					Status, Rev, Seq, Code, Reason string
					ConflictNote                   string `json:"conflict_note"`
					ConflictRev                    string `json:"conflict_rev"`
					ConflictSeq                    string `json:"conflict_seq"`
				}
			}
			Expect struct {
				HeadSeq      string `json:"head_seq"`
				Notes        []entity
				Changes      int
				ProcessedOps int `json:"processed_ops"`
			}
		}
	}
	if e = json.Unmarshal(raw, &suite); e != nil {
		t.Fatal(e)
	}
	if suite.SchemaVersion != 1 || len(suite.Cases) < 10 {
		t.Fatal("missing shared conformance cases")
	}
	for _, c := range suite.Cases {
		t.Run(c.CaseID, func(t *testing.T) {
			env := setup(t)
			a := env.account(t, "alice")
			bstatus, braw, _ := env.request("POST", "/v1/auth/login", "", map[string]string{"username": "alice", "password": "correct long password", "device_id": uuid(), "device_name": "设备 B"})
			if bstatus != 200 {
				t.Fatal(string(braw))
			}
			var b Session
			_ = json.Unmarshal(braw, &b)
			sessions := map[string]Session{"A": a, "B": b}
			ids := map[string]string{}
			resolve := func(symbol string) string {
				if v := ids[symbol]; v != "" {
					return v
				}
				ids[symbol] = uuid()
				return ids[symbol]
			}
			for _, n := range c.Initial {
				id := resolve(n.Note)
				now := stamp(env.store.now())
				snapshot := Note{id, n.Content, n.Deleted, n.Rev, n.Seq, now, now, nil}
				raw, _ := json.Marshal(snapshot)
				_, e := env.store.db.Exec("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,NULL)", a.UserID, id, n.Content, n.Deleted, n.Rev, n.Seq, now, now)
				if e != nil {
					t.Fatal(e)
				}
				_, e = env.store.db.Exec("INSERT INTO changes VALUES(?,?,?)", a.UserID, n.Seq, string(raw))
				if e != nil {
					t.Fatal(e)
				}
				_, e = env.store.db.Exec("UPDATE user_sync_state SET head_seq=? WHERE user_id=?", n.Seq, a.UserID)
				if e != nil {
					t.Fatal(e)
				}
			}
			for _, o := range c.Ops {
				result := env.push(t, sessions[o.Device], Operation{resolve(o.Op), resolve(o.Note), o.BaseRev, o.Content, o.Deleted})[0]
				x := o.Expect
				if result.Status != x.Status || x.Rev != "" && result.Rev != x.Rev || x.Seq != "" && result.Seq != x.Seq {
					t.Fatalf("%s result %+v expected %+v", o.Op, result, x)
				}
				if x.Code != "" && (result.Error == nil || result.Error.Code != x.Code) {
					t.Fatalf("error %+v expected %s", result, x.Code)
				}
				if x.ConflictNote != "" {
					ids[x.ConflictNote] = result.ConflictNoteID
					if result.ConflictRev != x.ConflictRev || result.ConflictSeq != x.ConflictSeq {
						t.Fatalf("conflict result %+v", result)
					}
					n, e := scanNote(env.store.db.QueryRow("SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", a.UserID, result.ConflictNoteID))
					if e != nil || n.Conflict == nil || n.Conflict.Reason != x.Reason || n.Conflict.SourceDeviceID != sessions[o.Device].DeviceID {
						t.Fatalf("conflict metadata %+v: %v", n.Conflict, e)
					}
				}
			}
			h, e := env.store.head(context.Background(), a.UserID)
			if e != nil || num(h) != c.Expect.HeadSeq {
				t.Fatalf("head %d: %v", h, e)
			}
			for _, n := range c.Expect.Notes {
				actual, e := scanNote(env.store.db.QueryRow("SELECT "+noteColumns+" FROM notes WHERE user_id=? AND id=?", a.UserID, resolve(n.Note)))
				if e != nil || actual.Content != n.Content || actual.Deleted != n.Deleted || actual.Rev != n.Rev || actual.Seq != n.Seq {
					t.Fatalf("note %s %+v expected %+v (%v)", n.Note, actual, n, e)
				}
			}
			for table, want := range map[string]int{"notes": len(c.Expect.Notes), "changes": c.Expect.Changes, "processed_ops": c.Expect.ProcessedOps} {
				var count int
				if e := env.store.db.QueryRow("SELECT count(*) FROM "+table+" WHERE user_id=?", a.UserID).Scan(&count); e != nil || count != want {
					t.Fatalf("%s=%d want %d: %v", table, count, want, e)
				}
			}
		})
	}
}

func TestTransactionRollbackAndRestart(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	op := Operation{uuid(), uuid(), "0", "事务正文", false}
	for _, point := range []string{"after_note", "before_commit"} {
		env.store.failpoint = func(at string) error {
			if at == point {
				return errors.New("injected disk failure")
			}
			return nil
		}
		r := env.push(t, a, op)[0]
		if r.Error == nil || r.Error.Code != "INTERNAL_ERROR" {
			t.Fatalf("not failed: %+v", r)
		}
		h, _ := env.store.head(context.Background(), a.UserID)
		if h != 0 {
			t.Fatal("partial commit")
		}
		for _, table := range []string{"notes", "changes", "processed_ops"} {
			var count int
			_ = env.store.db.QueryRow("SELECT count(*) FROM " + table).Scan(&count)
			if count != 0 {
				t.Fatalf("partial %s", table)
			}
		}
	}
	env.store.failpoint = nil
	first := env.push(t, a, op)[0]
	if first.Status != "accepted" {
		t.Fatal(first)
	}
	second := env.push(t, a, op)[0]
	if !reflect.DeepEqual(first, second) {
		t.Fatal("retry differs")
	}
	path := filepath.Join(t.TempDir(), "restart.db")
	if e := env.store.Backup(context.Background(), path); e != nil {
		t.Fatal(e)
	}
	copy, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { _ = copy.Close() }()
	got, changed, e := copy.push(context.Background(), env.identity(t, a), op)
	if e != nil || changed || !reflect.DeepEqual(got, first) {
		t.Fatalf("restart retry %+v %v %v", got, changed, e)
	}
}
func TestAuthIsolationAndDeviceRevocation(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	b := env.account(t, "bob")
	op := Operation{uuid(), uuid(), "0", "private A", false}
	env.push(t, a, op)
	op.Content = "private B"
	env.push(t, b, op)
	for _, session := range []Session{a, b} {
		status, raw, _ := env.request("GET", "/v1/sync/pull?epoch="+env.epoch+"&cursor=0", session.Token, nil)
		if status != 200 {
			t.Fatal(status)
		}
		var p PullPage
		_ = json.Unmarshal(raw, &p)
		want := "private A"
		if session.UserID == b.UserID {
			want = "private B"
		}
		if len(p.Changes) != 1 || p.Changes[0].Content != want {
			t.Fatal("account leaked")
		}
	}
	status, _, _ := env.request("DELETE", "/v1/devices/"+b.DeviceID, a.Token, nil)
	if status != 404 {
		t.Fatal("cross account revoke", status)
	}
	status, raw, _ := env.request("GET", "/v1/export", a.Token, nil)
	if status != 200 {
		t.Fatal(status)
	}
	zr, e := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if e != nil {
		t.Fatal(e)
	}
	for _, f := range zr.File {
		rc, _ := f.Open()
		content, _ := io.ReadAll(rc)
		_ = rc.Close()
		if strings.Contains(string(content), "private B") {
			t.Fatal("export leaked")
		}
	}
	status, _, _ = env.request("POST", "/v1/auth/logout", a.Token, nil)
	if status != 204 {
		t.Fatal(status)
	}
	status, _, _ = env.request("GET", "/v1/sync/status", a.Token, nil)
	if status != 401 {
		t.Fatal("revoked token accepted")
	}
	status, raw, _ = env.request("POST", "/v1/auth/login", "", map[string]string{"username": "alice", "password": "correct long password", "device_id": b.DeviceID, "device_name": "stolen"})
	if status != 409 || !bytes.Contains(raw, []byte("DEVICE_ID_UNAVAILABLE")) {
		t.Fatal("device ownership", status, string(raw))
	}
}
func TestInvitationConcurrencyAtomicityAndPasswords(t *testing.T) {
	env := setup(t)
	_, code, e := env.store.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	statuses := make(chan int, 2)
	for _, name := range []string{"alice", "bob"} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _, _ := env.request("POST", "/v1/auth/register", "", map[string]string{"username": name, "password": "correct long password", "device_id": uuid(), "device_name": "device", "invite_code": code})
			statuses <- status
		}()
	}
	wg.Wait()
	close(statuses)
	counts := map[int]int{}
	for status := range statuses {
		counts[status]++
	}
	if counts[201] != 1 || counts[403] != 1 {
		t.Fatal(counts)
	}
	var username, phc, tokenHash string
	if e = env.store.db.QueryRow("SELECT username,password_hash FROM users").Scan(&username, &phc); e != nil {
		t.Fatal(e)
	}
	if !strings.HasPrefix(phc, "$argon2id$v=19$m=19456,t=2,p=1$") || !passwordOK(phc, "correct long password") || passwordOK(phc, "incorrect long password") {
		t.Fatal("password hashing")
	}
	if e = env.store.db.QueryRow("SELECT token_hash FROM devices").Scan(&tokenHash); e != nil || len(tokenHash) != 64 {
		t.Fatal("token storage")
	}
	if e = env.store.ResetPassword(context.Background(), username, "replacement password 123"); e != nil {
		t.Fatal(e)
	}
	var revoked int
	_ = env.store.db.QueryRow("SELECT count(*) FROM devices WHERE revoked_at IS NOT NULL").Scan(&revoked)
	if revoked != 1 {
		t.Fatal("reset did not revoke")
	}
	// Failure before commit must not consume an invitation or leave a user/device.
	_, code, e = env.store.CreateInvitation(context.Background(), time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	env.store.failpoint = func(point string) error {
		if point == "register_before_commit" {
			return errors.New("injected")
		}
		return nil
	}
	status, _, _ := env.request("POST", "/v1/auth/register", "", map[string]string{"username": "carol", "password": "correct long password", "device_id": uuid(), "device_name": "device", "invite_code": code})
	if status != 500 {
		t.Fatal(status)
	}
	env.store.failpoint = nil
	var used sql.NullString
	_ = env.store.db.QueryRow("SELECT used_by FROM invitations WHERE code_hash=?", hash(code)).Scan(&used)
	if used.Valid {
		t.Fatal("invitation partially consumed")
	}
}
func TestAuthRateLimitsAndExpiry(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	for i := 0; i < 5; i++ {
		status, _, _ := env.request("POST", "/v1/auth/login", "", map[string]string{"username": "alice", "password": "wrong long password", "device_id": uuid(), "device_name": "x"})
		if status != 401 {
			t.Fatalf("failure %d status %d", i, status)
		}
	}
	status, _, headers := env.request("POST", "/v1/auth/login", "", map[string]string{"username": "alice", "password": "correct long password", "device_id": a.DeviceID, "device_name": "x"})
	if status != 429 || headers.Get("Retry-After") == "" {
		t.Fatal("missing limiter", status)
	}
	fixed := time.Now().Add(91 * 24 * time.Hour)
	env.store.now = func() time.Time { return fixed }
	status, _, _ = env.request("GET", "/v1/sync/status", a.Token, nil)
	if status != 401 {
		t.Fatal("expired token accepted")
	}
	env.api.cfg.RegisterAttempts = 1
	for i := 0; i < 2; i++ {
		status, _, headers = env.request("POST", "/v1/auth/register", "", []byte(`{}`))
		if i == 0 && status != 400 || i == 1 && (status != 429 || headers.Get("Retry-After") == "") {
			t.Fatalf("register limiter %d %d", i, status)
		}
	}
}
func TestFixedUntilAndResponseByteBudget(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	for i := 0; i < 5; i++ {
		env.push(t, a, Operation{uuid(), uuid(), "0", fmt.Sprint(i), false})
	}
	p, e := env.store.pull(context.Background(), a.UserID, env.epoch, 0, -1, 2)
	if e != nil || p.Until != "5" || p.NextCursor != "2" || !p.HasMore {
		t.Fatalf("first page %+v %v", p, e)
	}
	env.push(t, a, Operation{uuid(), uuid(), "0", "concurrent", false})
	p, e = env.store.pull(context.Background(), a.UserID, env.epoch, 2, 5, 2)
	if e != nil || p.NextCursor != "4" || p.Until != "5" {
		t.Fatal(p, e)
	}
	p, e = env.store.pull(context.Background(), a.UserID, env.epoch, 4, 5, 2)
	if e != nil || p.NextCursor != "5" || p.HasMore || p.Changes[0].Content != "4" {
		t.Fatal(p, e)
	}
	for _, query := range []string{"cursor=7", "cursor=2&until=1", "cursor=0&until=7", "cursor=01", "cursor=-1", "cursor=9223372036854775808", "cursor=0&limit=0"} {
		status, _, _ := env.request("GET", "/v1/sync/pull?epoch="+env.epoch+"&"+query, a.Token, nil)
		if status != 409 && status != 400 {
			t.Fatal(query, status)
		}
	}
	// U+0000 takes six JSON bytes; one maximum note must fit, two must page.
	for i := 0; i < 2; i++ {
		env.push(t, a, Operation{uuid(), uuid(), "0", strings.Repeat("\x00", maxContent), false})
	}
	status, raw, _ := env.request("GET", "/v1/sync/pull?epoch="+env.epoch+"&cursor=6&limit=100", a.Token, nil)
	if status != 200 || len(raw) > maxBody {
		t.Fatalf("byte budget %d %d", status, len(raw))
	}
	_ = json.Unmarshal(raw, &p)
	if len(p.Changes) != 1 || !p.HasMore || p.NextCursor != "7" {
		t.Fatal("did not byte-page", len(p.Changes), p.NextCursor, p.HasMore)
	}
}
func TestStrictRequestValidation(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	good := Operation{uuid(), uuid(), "0", "甲", false}
	valid, _ := json.Marshal(good)
	invalids := []string{`null`, `{}`, `{"op_id":"` + good.OpID + `","note_id":"` + good.NoteID + `","base_rev":"0","content":"x","deleted":false,"unexpected":1}`, `{"op_id":"` + uuid() + `","note_id":"` + uuid() + `","base_rev":"0","content":"x","deleted":null}`}
	for _, bad := range invalids {
		payload := []byte(`{"epoch":"` + env.epoch + `","ops":[` + string(valid) + `,` + bad + `]}`)
		status, raw, _ := env.request("POST", "/v1/sync/push", a.Token, payload)
		if status != 200 {
			t.Fatal(status, string(raw))
		}
		var p struct{ Results []Result }
		_ = json.Unmarshal(raw, &p)
		if len(p.Results) != 2 || p.Results[0].Status != "accepted" || p.Results[1].Status != "error" {
			t.Fatal(string(raw))
		}
	}
	for _, payload := range [][]byte{[]byte(`{"epoch":"` + env.epoch + `","ops":[` + string(valid) + `],"unknown":1}`), []byte(`{"epoch":"` + env.epoch + `","ops":[]}`), []byte(`{"epoch":"` + env.epoch + `","ops":null}`), bytes.Repeat([]byte(" "), maxBody+1)} {
		status, _, _ := env.request("POST", "/v1/sync/push", a.Token, payload)
		if status != 400 && status != 413 {
			t.Fatal("invalid outer accepted", status)
		}
	}
	r := env.push(t, a, Operation{uuid(), uuid(), "0", strings.Repeat("x", maxContent+1), false})[0]
	if r.Error == nil || r.Error.Code != "NOTE_TOO_LARGE" {
		t.Fatal(r)
	}
	r = env.push(t, a, Operation{uuid(), uuid(), "0", "CR\rLF", false})[0]
	if r.Error == nil || r.Error.Code != "INVALID_REQUEST" {
		t.Fatal(r)
	}
	status, _, _ := env.request("POST", "/v1/sync/push", a.Token, map[string]any{"epoch": uuid(), "ops": []Operation{good}})
	if status != 409 {
		t.Fatal(status)
	}
}
func TestSSESignalReplacementAndRevocation(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	open := func() *http.Response {
		req, _ := http.NewRequest("GET", env.http.URL+"/v1/events", nil)
		req.Header.Set("Authorization", "Bearer "+a.Token)
		resp, e := http.DefaultClient.Do(req)
		if e != nil {
			t.Fatal(e)
		}
		if resp.StatusCode != 200 {
			t.Fatal(resp.StatusCode)
		}
		return resp
	}
	first := open()
	defer func() { _ = first.Body.Close() }()
	reader := bufio.NewReader(first.Body)
	line, e := reader.ReadString('\n')
	if e != nil || line != "event: sync\n" {
		t.Fatal(line, e)
	}
	second := open()
	defer func() { _ = second.Body.Close() }()
	finished := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, first.Body); close(finished) }()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
		t.Fatal("old SSE remained open")
	}
	reader = bufio.NewReader(second.Body)
	for i := 0; i < 3; i++ {
		_, _ = reader.ReadString('\n')
	}
	env.push(t, a, Operation{uuid(), uuid(), "0", "notify", false})
	found := false
	for i := 0; i < 20; i++ {
		line, e = reader.ReadString('\n')
		if e != nil {
			t.Fatal(e)
		}
		if strings.Contains(line, `"head_seq":"1"`) {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("no commit notification")
	}
	status, _, _ := env.request("POST", "/v1/auth/logout", a.Token, nil)
	if status != 204 {
		t.Fatal(status)
	}
	finished = make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, second.Body); close(finished) }()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
		t.Fatal("revoked SSE remained open")
	}
}
func TestMigrationEpochAndBackup(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "memo.db")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	var mode string
	_ = s.db.QueryRow("PRAGMA journal_mode").Scan(&mode)
	if mode != "wal" {
		t.Fatal(mode)
	}
	var full, foreign int
	_ = s.db.QueryRow("PRAGMA synchronous").Scan(&full)
	_ = s.db.QueryRow("PRAGMA foreign_keys").Scan(&foreign)
	if full != 2 || foreign != 1 {
		t.Fatal("unsafe PRAGMA")
	}
	if e = s.Backup(context.Background(), filepath.Join(dir, "backup.db")); e != nil {
		t.Fatal(e)
	}
	if e = s.Backup(context.Background(), filepath.Join(dir, "backup.db")); e == nil {
		t.Fatal("backup overwrite")
	}
	if e = s.IntegrityCheck(context.Background()); e != nil {
		t.Fatal(e)
	}
	_, e = s.db.Exec("PRAGMA user_version=2")
	if e != nil {
		t.Fatal(e)
	}
	_ = s.Close()
	if db, e := Open(path); e == nil {
		_ = db.Close()
		t.Fatal("future schema opened")
	}
	restored, e := Open(filepath.Join(dir, "backup.db"))
	if e != nil {
		t.Fatal(e)
	}
	_, e = restored.db.Exec("UPDATE schema_migrations SET checksum='tampered'")
	if e != nil {
		t.Fatal(e)
	}
	_ = restored.Close()
	if db, e := Open(filepath.Join(dir, "backup.db")); e == nil {
		_ = db.Close()
		t.Fatal("tampered migration accepted")
	}
	ep := filepath.Join(dir, "runtime", "epoch")
	v, e := LoadEpoch(ep, false)
	if e != nil || !uuidPattern.MatchString(v) {
		t.Fatal(v, e)
	}
	again, e := LoadEpoch(ep, true)
	if e != nil || again != v {
		t.Fatal("epoch changed on restart")
	}
	lock, e := LockEpoch(ep)
	if e != nil {
		t.Fatal(e)
	}
	if second, e := LockEpoch(ep); e == nil {
		_ = second.Close()
		t.Fatal("multiple epoch locks")
	}
	_ = lock.Close()
	_ = os.Remove(ep)
	if _, e = LoadEpoch(ep, true); e == nil {
		t.Fatal("lost epoch silently recreated")
	}
	rotated, e := RotateEpoch(ep)
	if e != nil || rotated == v {
		t.Fatal("epoch not rotated")
	}
}

func TestConcurrentPushAndDiskFull(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	original := Operation{uuid(), uuid(), "0", "initial", false}
	env.push(t, a, original)
	var wg sync.WaitGroup
	results := make(chan Result, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, changed, e := env.store.push(context.Background(), env.identity(t, a), Operation{uuid(), original.NoteID, "1", "concurrent", false})
			if e != nil || !changed {
				t.Errorf("write failed: %v", e)
			}
			results <- r
		}()
	}
	wg.Wait()
	close(results)
	counts := map[string]int{}
	for r := range results {
		counts[r.Status]++
	}
	if counts["accepted"] != 1 || counts["conflict"] != 11 {
		t.Fatal(counts)
	}
	head, _ := env.store.head(context.Background(), a.UserID)
	if head != 13 {
		t.Fatal(head)
	}
	var pages int
	if e := env.store.db.QueryRow("PRAGMA page_count").Scan(&pages); e != nil {
		t.Fatal(e)
	}
	if _, e := env.store.db.Exec(fmt.Sprintf("PRAGMA max_page_count=%d", pages)); e != nil {
		t.Fatal(e)
	}
	op := Operation{uuid(), uuid(), "0", strings.Repeat("x", maxContent), false}
	r, changed, e := env.store.push(context.Background(), env.identity(t, a), op)
	if e == nil || changed {
		t.Fatal("disk full accepted", r, e)
	}
	after, _ := env.store.head(context.Background(), a.UserID)
	if after != head {
		t.Fatal("disk full partial commit")
	}
	var count int
	_ = env.store.db.QueryRow("SELECT count(*) FROM notes WHERE user_id=? AND id=?", a.UserID, op.NoteID).Scan(&count)
	if count != 0 {
		t.Fatal("disk full partial entity")
	}
	if _, e = env.store.db.Exec("PRAGMA max_page_count=1073741823"); e != nil {
		t.Fatal(e)
	}
	r, changed, e = env.store.push(context.Background(), env.identity(t, a), op)
	if e != nil || !changed || r.Status != "accepted" {
		t.Fatalf("disk recovery %v %v %+v", e, changed, r)
	}
}
func TestMigrationFailureRollsBackAndUpgradeEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	db, e := sql.Open("sqlite", path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("CREATE TABLE notes(existing TEXT)"); e != nil {
		t.Fatal(e)
	}
	_ = db.Close()
	if s, e := Open(path); e == nil {
		_ = s.Close()
		t.Fatal("conflicting migration accepted")
	}
	db, e = sql.Open("sqlite", path)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { _ = db.Close() }()
	var users int
	if e = db.QueryRow("SELECT count(*) FROM sqlite_master WHERE name='users'").Scan(&users); e != nil {
		t.Fatal(e)
	}
	if users != 0 {
		t.Fatal("migration partially committed")
	}
	if _, e = db.Exec("DROP TABLE notes"); e != nil {
		t.Fatal(e)
	}
	_ = db.Close()
	s, e := Open(path)
	if e != nil {
		t.Fatal("version 0 to 1 failed", e)
	}
	defer func() { _ = s.Close() }()
	var version int
	_ = s.db.QueryRow("PRAGMA user_version").Scan(&version)
	if version != 1 {
		t.Fatal("upgrade version", version)
	}
}
func TestZIPManifestAndTombstones(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	active := Operation{uuid(), uuid(), "0", "# 标题\n正文🙂", false}
	deleted := Operation{uuid(), uuid(), "0", "墓碑正文", true}
	env.push(t, a, active, deleted)
	for _, include := range []bool{false, true} {
		path := "/v1/export"
		if include {
			path += "?include_deleted=true"
		}
		status, raw, _ := env.request("GET", path, a.Token, nil)
		if status != 200 {
			t.Fatal(status)
		}
		zr, e := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
		if e != nil {
			t.Fatal(e)
		}
		var m manifest
		files := map[string]string{}
		for _, entry := range zr.File {
			rc, e := entry.Open()
			if e != nil {
				t.Fatal(e)
			}
			contents, e := io.ReadAll(rc)
			_ = rc.Close()
			if e != nil {
				t.Fatal(e)
			}
			if entry.Name == "manifest.json" {
				if e = json.Unmarshal(contents, &m); e != nil {
					t.Fatal(e)
				}
			} else {
				if strings.ContainsAny(entry.Name, "/\\") {
					t.Fatal("unsafe ZIP path")
				}
				files[entry.Name] = string(contents)
			}
		}
		want := 1
		if include {
			want = 2
		}
		if m.SourceKind != "server" || m.FormatVersion != 1 || len(m.Notes) != want || len(files) != want {
			t.Fatalf("manifest %+v files %+v", m, files)
		}
		if files[active.NoteID+".md"] != active.Content {
			t.Fatal("export changed content")
		}
		for _, n := range m.Notes {
			if n.File != n.ID+".md" {
				t.Fatal("manifest path")
			}
			if n.ID == active.NoteID && n.Title != "标题" {
				t.Fatal("derived title", n.Title)
			}
		}
	}
}
func TestProductionTransportAndProxyIsolation(t *testing.T) {
	env := setup(t)
	a := env.account(t, "alice")
	api, e := New(env.store, Config{Epoch: env.epoch, TrustedProxies: []string{"127.0.0.1/32"}})
	if e != nil {
		t.Fatal(e)
	}
	for _, tc := range []struct {
		remote, proto string
		want          int
	}{{"127.0.0.1:1234", "", 400}, {"127.0.0.1:1234", "https", 200}, {"203.0.113.1:1234", "https", 400}} {
		r := httptest.NewRequest("GET", "http://memo/v1/sync/status", nil)
		r.RemoteAddr = tc.remote
		r.Header.Set("Authorization", "Bearer "+a.Token)
		r.Header.Set("X-Forwarded-Proto", tc.proto)
		w := httptest.NewRecorder()
		api.Handler().ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatal(tc, w.Code)
		}
	}
	r := httptest.NewRequest("GET", "http://memo/v1/sync/status", nil)
	r.RemoteAddr = "203.0.113.1:1234"
	r.Header.Set("X-Forwarded-For", "127.0.0.1")
	if api.clientIP(r) != "203.0.113.1" {
		t.Fatal("spoofed proxy trusted")
	}
}
