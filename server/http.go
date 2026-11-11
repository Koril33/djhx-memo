package memo

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Config configures the HTTP service. Production trusts forwarding headers only from TrustedProxies.
type Config struct {
	Epoch                string
	DevHTTP              bool
	TrustedProxies       []string
	RegistrationDisabled bool
	LoginFailures        int
	RegisterAttempts     int
	Heartbeat            time.Duration
}
type rateEntry struct {
	count   int
	expires time.Time
}
type stream struct {
	user   string
	signal chan struct{}
	stop   chan struct{}
}

// Server implements the v1 HTTP API. Call Handler to mount it on a standard HTTP server.
type Server struct {
	store     *Store
	cfg       Config
	proxy     []*net.IPNet
	dummyHash string
	mu        sync.Mutex
	rates     map[string]rateEntry
	streams   map[string]*stream
	authSlots chan struct{}
}

// New validates configuration and initializes request limiting and SSE coordination.
func New(store *Store, cfg Config) (*Server, error) {
	if !uuidPattern.MatchString(cfg.Epoch) {
		return nil, errors.New("valid epoch required")
	}
	if cfg.LoginFailures <= 0 {
		cfg.LoginFailures = 5
	}
	if cfg.RegisterAttempts <= 0 {
		cfg.RegisterAttempts = 5
	}
	if cfg.Heartbeat <= 0 {
		cfg.Heartbeat = 15 * time.Second
	}
	s := &Server{store: store, cfg: cfg, dummyHash: passwordHash(randomToken()), rates: map[string]rateEntry{}, streams: map[string]*stream{}, authSlots: make(chan struct{}, 4)}
	for _, cidr := range cfg.TrustedProxies {
		_, block, e := net.ParseCIDR(cidr)
		if e != nil {
			return nil, e
		}
		s.proxy = append(s.proxy, block)
	}
	return s, nil
}

// Handler returns the strict, non-CORS v1 API handler.
func (s *Server) Handler() http.Handler { return http.HandlerFunc(s.serve) }
func (s *Server) trusted(ip net.IP) bool {
	for _, block := range s.proxy {
		if block.Contains(ip) {
			return true
		}
	}
	return false
}
func (s *Server) clientIP(r *http.Request) string {
	host, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		return "unknown"
	}
	ip := net.ParseIP(host)
	if s.trusted(ip) {
		chain := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
		for i := len(chain) - 1; i >= 0; i-- {
			next := net.ParseIP(strings.TrimSpace(chain[i]))
			if next == nil {
				return host
			}
			host = next.String()
			if !s.trusted(next) {
				break
			}
		}
	}
	return host
}
func (s *Server) secure(r *http.Request) bool {
	if s.cfg.DevHTTP || r.TLS != nil {
		return true
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	return s.trusted(net.ParseIP(host)) && r.Header.Get("X-Forwarded-Proto") == "https"
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	raw, e := json.Marshal(v)
	if e != nil {
		http.Error(w, "INTERNAL_ERROR", 500)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(append(raw, '\n'))
}
func httpStatus(code string) int {
	switch code {
	case "INVALID_REQUEST":
		return 400
	case "PAYLOAD_TOO_LARGE":
		return 413
	case "AUTH_REQUIRED", "AUTH_INVALID_CREDENTIALS":
		return 401
	case "INVITE_INVALID":
		return 403
	case "NOT_FOUND":
		return 404
	case "DEVICE_ID_UNAVAILABLE", "USERNAME_TAKEN", "OP_ID_REUSED", "CURSOR_INVALID", "SYNC_EPOCH_MISMATCH", "PURGE_CONFLICT", "NOTE_PURGED":
		return 409
	case "NOTE_TOO_LARGE", "NOTE_NOT_FOUND", "INVALID_BASE_REV":
		return 422
	case "PROTOCOL_UNSUPPORTED":
		return 426
	case "RATE_LIMITED":
		return 429
	case "SERVER_BUSY":
		return 503
	default:
		return 500
	}
}
func writeError(w http.ResponseWriter, r *http.Request, e error) {
	a := internal(e)
	writeJSON(w, httpStatus(a.Code), map[string]any{"error": a, "request_id": r.Header.Get("X-Internal-Request-ID")})
}
func readJSON(w http.ResponseWriter, r *http.Request, max int, dst any) error {
	if media := strings.Split(r.Header.Get("Content-Type"), ";")[0]; media != "application/json" {
		return apiError("INVALID_REQUEST")
	}
	r.Body = http.MaxBytesReader(w, r.Body, int64(max))
	raw, e := io.ReadAll(r.Body)
	if e != nil {
		var large *http.MaxBytesError
		if errors.As(e, &large) {
			return apiError("PAYLOAD_TOO_LARGE")
		}
		return apiError("INVALID_REQUEST")
	}
	return decode(raw, dst)
}
func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	requestID := uuid()
	r.Header.Set("X-Internal-Request-ID", requestID)
	w.Header().Set("X-Request-ID", requestID)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Header.Get("Origin") != "" {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if r.URL.Path == "/healthz" && r.Method == http.MethodGet {
		if s.store.Healthy(r.Context()) != nil {
			writeError(w, r, apiError("SERVER_BUSY"))
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok"})
		return
	}
	if !s.secure(r) {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if (r.URL.Path == "/v1/auth/register" || r.URL.Path == "/v1/auth/login") && r.Method == http.MethodPost {
		s.auth(w, r)
		return
	}
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") || strings.Count(header, " ") != 1 {
		writeError(w, r, apiError("AUTH_REQUIRED"))
		return
	}
	a, e := s.store.authenticate(r.Context(), strings.TrimPrefix(header, "Bearer "))
	if e != nil {
		writeError(w, r, e)
		return
	}
	if (strings.HasPrefix(r.URL.Path, "/v1/sync/") && r.URL.Path != "/v1/sync/status" || r.URL.Path == "/v1/events" || r.URL.Path == "/v1/export" || r.URL.Path == "/v1/notes/purge") && r.Header.Get("X-Memo-Protocol") != "2" {
		writeError(w, r, apiError("PROTOCOL_UNSUPPORTED"))
		return
	}
	switch {
	case r.URL.Path == "/v1/auth/logout" && r.Method == http.MethodPost:
		e = s.store.revoke(r.Context(), a, a.DeviceID)
		if e == nil {
			s.stopStream(a.DeviceID)
			w.WriteHeader(204)
			return
		}
	case r.URL.Path == "/v1/devices" && r.Method == http.MethodGet:
		s.devices(w, r, a)
		return
	case strings.HasPrefix(r.URL.Path, "/v1/devices/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(r.URL.Path, "/v1/devices/")
		if !uuidPattern.MatchString(id) {
			e = apiError("NOT_FOUND")
		} else {
			e = s.store.revoke(r.Context(), a, id)
		}
		if e == nil {
			s.stopStream(id)
			w.WriteHeader(204)
			return
		}
	case r.URL.Path == "/v1/sync/status" && r.Method == http.MethodGet:
		h, err := s.store.head(r.Context(), a.UserID)
		e = err
		if e == nil {
			writeJSON(w, 200, map[string]any{"epoch": s.cfg.Epoch, "head_seq": num(h), "protocol_version": 2})
			return
		}
	case r.URL.Path == "/v1/sync/push" && r.Method == http.MethodPost:
		s.pushHTTP(w, r, a)
		return
	case r.URL.Path == "/v1/notes/purge" && r.Method == http.MethodPost:
		s.purgeHTTP(w, r, a)
		return
	case r.URL.Path == "/v1/sync/pull" && r.Method == http.MethodGet:
		s.pullHTTP(w, r, a)
		return
	case r.URL.Path == "/v1/events" && r.Method == http.MethodGet:
		s.events(w, r, a)
		return
	case r.URL.Path == "/v1/export" && r.Method == http.MethodGet:
		s.exportHTTP(w, r, a)
		return
	default:
		e = apiError("NOT_FOUND")
	}
	writeError(w, r, e)
}
func (s *Server) limited(key string, threshold int, window time.Duration, add bool) (bool, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.store.now()
	entry := s.rates[key]
	if !now.Before(entry.expires) {
		entry = rateEntry{expires: now.Add(window)}
	}
	if entry.count >= threshold {
		return true, int(entry.expires.Sub(now).Seconds()) + 1
	}
	if add {
		entry.count++
	}
	if len(s.rates) > 10000 {
		for k, v := range s.rates {
			if !now.Before(v.expires) {
				delete(s.rates, k)
			}
		}
		if len(s.rates) > 10000 {
			return true, 60
		}
	}
	s.rates[key] = entry
	return false, 0
}
func (s *Server) auth(w http.ResponseWriter, r *http.Request) {
	register := r.URL.Path == "/v1/auth/register"
	ip := s.clientIP(r)
	if register {
		if s.cfg.RegistrationDisabled {
			writeError(w, r, apiError("INVITE_INVALID"))
			return
		}
		if limited, retry := s.limited("register:"+ip, s.cfg.RegisterAttempts, time.Hour, true); limited {
			w.Header().Set("Retry-After", strconv.Itoa(retry))
			writeError(w, r, apiError("RATE_LIMITED"))
			return
		}
	}
	var raw json.RawMessage
	if e := readJSON(w, r, 16384, &raw); e != nil {
		writeError(w, r, e)
		return
	}
	var a authRequest
	// Login and registration have distinct allowed fields and require every field.
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(raw, &fields)
	want := 4
	if register {
		want = 5
	}
	if len(fields) != want {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	for _, key := range []string{"username", "password", "device_id", "device_name"} {
		if len(fields[key]) == 0 || bytes.Equal(fields[key], []byte("null")) {
			writeError(w, r, apiError("INVALID_REQUEST"))
			return
		}
	}
	if register && (len(fields["invite_code"]) == 0 || bytes.Equal(fields["invite_code"], []byte("null"))) {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if !register && fields["invite_code"] != nil {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if e := decode(raw, &a); e != nil || !validAuth(a, register) {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	keys := []string{"login-ip:" + ip, "login-user:" + a.Username}
	if !register {
		for _, key := range keys {
			if limited, retry := s.limited(key, s.cfg.LoginFailures, 15*time.Minute, false); limited {
				w.Header().Set("Retry-After", strconv.Itoa(retry))
				writeError(w, r, apiError("RATE_LIMITED"))
				return
			}
		}
	}
	// Bound concurrent Argon2 memory/CPU, independently of per-IP limits.
	select {
	case s.authSlots <- struct{}{}:
		defer func() { <-s.authSlots }()
	default:
		w.Header().Set("Retry-After", "1")
		writeError(w, r, apiError("SERVER_BUSY"))
		return
	}
	var session Session
	var e error
	if register {
		session, e = s.store.register(r.Context(), a, s.cfg.Epoch)
	} else {
		session, e = s.store.login(r.Context(), a, s.cfg.Epoch, s.dummyHash)
	}
	if e != nil {
		if !register && internal(e).Code == "AUTH_INVALID_CREDENTIALS" {
			for _, key := range keys {
				_, _ = s.limited(key, s.cfg.LoginFailures, 15*time.Minute, true)
			}
		}
		writeError(w, r, e)
		return
	}
	s.stopStream(a.DeviceID)
	status := 200
	if register {
		status = 201
	}
	writeJSON(w, status, session)
}
func (s *Server) pushHTTP(w http.ResponseWriter, r *http.Request, a identity) {
	var req struct {
		Epoch string            `json:"epoch"`
		Ops   []json.RawMessage `json:"ops"`
	}
	var outer json.RawMessage
	if e := readJSON(w, r, maxBody, &outer); e != nil {
		writeError(w, r, e)
		return
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(outer, &fields) != nil || len(fields) != 2 || len(fields["epoch"]) == 0 || len(fields["ops"]) == 0 || decode(outer, &req) != nil {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if !uuidPattern.MatchString(req.Epoch) || len(req.Ops) < 1 || len(req.Ops) > 50 {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if req.Epoch != s.cfg.Epoch {
		writeError(w, r, apiError("SYNC_EPOCH_MISMATCH"))
		return
	}
	results := make([]Result, 0, len(req.Ops))
	for _, raw := range req.Ops {
		o, id, validation := validateOp(raw)
		if validation != nil {
			results = append(results, resultErr(id, validation))
			continue
		}
		result, changed, e := s.store.push(r.Context(), a, o)
		if e != nil {
			result = resultErr(id, internal(e))
		}
		results = append(results, result)
		if changed && e == nil {
			s.notify(a.UserID)
		}
	}
	writeJSON(w, 200, map[string]any{"epoch": s.cfg.Epoch, "results": results})
}
func (s *Server) pullHTTP(w http.ResponseWriter, r *http.Request, a identity) {
	q := r.URL.Query()
	for k, values := range q {
		if (k != "epoch" && k != "cursor" && k != "until" && k != "limit") || len(values) != 1 {
			writeError(w, r, apiError("INVALID_REQUEST"))
			return
		}
	}
	if !uuidPattern.MatchString(q.Get("epoch")) {
		writeError(w, r, apiError("INVALID_REQUEST"))
		return
	}
	if q.Get("epoch") != s.cfg.Epoch {
		writeError(w, r, apiError("SYNC_EPOCH_MISMATCH"))
		return
	}
	cursor, e := counter(q.Get("cursor"))
	if e != nil {
		writeError(w, r, e)
		return
	}
	until := int64(-1)
	if values, ok := q["until"]; ok {
		until, e = counter(values[0])
		if e != nil {
			writeError(w, r, e)
			return
		}
	}
	limit := int64(50)
	if values, ok := q["limit"]; ok {
		limit, e = counter(values[0])
		if e != nil || limit < 1 || limit > 100 {
			writeError(w, r, apiError("INVALID_REQUEST"))
			return
		}
	}
	p, e := s.store.pull(r.Context(), a.UserID, s.cfg.Epoch, cursor, until, int(limit))
	if e != nil {
		writeError(w, r, e)
		return
	}
	writeJSON(w, 200, p)
}
func (s *Server) devices(w http.ResponseWriter, r *http.Request, a identity) {
	type device struct {
		ID         string  `json:"id"`
		Name       string  `json:"name"`
		CreatedAt  string  `json:"created_at"`
		LastSeenAt *string `json:"last_seen_at"`
		ExpiresAt  string  `json:"expires_at"`
		RevokedAt  *string `json:"revoked_at"`
		Current    bool    `json:"current"`
	}
	rows, e := s.store.db.QueryContext(r.Context(), "SELECT id,name,created_at,last_seen_at,expires_at,revoked_at FROM devices WHERE user_id=? ORDER BY created_at,id", a.UserID)
	if e != nil {
		writeError(w, r, e)
		return
	}
	list := []device{}
	for rows.Next() {
		var d device
		if e = rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &d.LastSeenAt, &d.ExpiresAt, &d.RevokedAt); e != nil {
			break
		}
		d.Current = d.ID == a.DeviceID
		list = append(list, d)
	}
	if e == nil {
		e = rows.Err()
	}
	_ = rows.Close()
	if e != nil {
		writeError(w, r, e)
		return
	}
	writeJSON(w, 200, map[string]any{"devices": list})
}
func (s *Server) stopStream(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if old, ok := s.streams[id]; ok {
		close(old.stop)
		delete(s.streams, id)
	}
}
func (s *Server) notify(user string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, st := range s.streams {
		if st.user == user {
			select {
			case st.signal <- struct{}{}:
			default:
			}
		}
	}
}
func (s *Server) events(w http.ResponseWriter, r *http.Request, a identity) {
	f, ok := w.(http.Flusher)
	if !ok {
		writeError(w, r, apiError("INTERNAL_ERROR"))
		return
	}
	st := &stream{user: a.UserID, signal: make(chan struct{}, 1), stop: make(chan struct{})}
	s.mu.Lock()
	if old, ok := s.streams[a.DeviceID]; ok {
		close(old.stop)
	}
	s.streams[a.DeviceID] = st
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.streams[a.DeviceID] == st {
			delete(s.streams, a.DeviceID)
		}
		s.mu.Unlock()
	}()
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("X-Accel-Buffering", "no")
	w.Header().Set("Connection", "keep-alive")
	send := func() bool {
		h, e := s.store.head(r.Context(), a.UserID)
		if e != nil {
			return false
		}
		raw, _ := json.Marshal(map[string]string{"epoch": s.cfg.Epoch, "head_seq": num(h)})
		_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, e = w.Write(append(append([]byte("event: sync\ndata: "), raw...), []byte("\n\n")...)); e != nil {
			return false
		}
		f.Flush()
		return true
	}
	if !send() {
		return
	}
	heartbeat := time.NewTicker(s.cfg.Heartbeat)
	defer heartbeat.Stop()
	authCheck := time.NewTicker(time.Second)
	defer authCheck.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-st.stop:
			return
		case <-st.signal:
			if e := validIdentity(r.Context(), s.store.db, a, stamp(s.store.now())); e != nil {
				return
			}
			if !send() {
				return
			}
		case <-authCheck.C:
			if e := validIdentity(r.Context(), s.store.db, a, stamp(s.store.now())); e != nil {
				return
			}
		case <-heartbeat.C:
			_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Second))
			if _, e := io.WriteString(w, ": heartbeat\n\n"); e != nil {
				return
			}
			f.Flush()
		}
	}
}

// Shutdown closes SSE streams before HTTP graceful shutdown waits for handlers.
func (s *Server) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	for id, st := range s.streams {
		close(st.stop)
		delete(s.streams, id)
	}
	s.mu.Unlock()
	return s.store.Healthy(ctx)
}
