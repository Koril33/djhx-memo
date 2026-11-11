//! Black-box conformance and recovery against the actual Go HTTP server.
//! Run with DJHX_SERVER_BIN=/absolute/memo-server cargo test --test server_integration -- --ignored.
use djhx_memo_core::{CredentialStore, MemoApp};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

fn uid() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// PURGE-02/03/05/06 through the actual Go HTTP handler, without a Unix CLI dependency.
#[test]
#[ignore = "requires isolated DJHX_PURGE_TEST_URL and DJHX_PURGE_TEST_INVITE"]
fn permanent_delete_http_conformance() {
    let service = if std::env::var("DJHX_PURGE_TEST_URL").is_err() {
        Some(Service::new())
    } else {
        None
    };
    let url = std::env::var("DJHX_PURGE_TEST_URL")
        .unwrap_or_else(|_| service.as_ref().unwrap().url.clone());
    let invitation = std::env::var("DJHX_PURGE_TEST_INVITE")
        .unwrap_or_else(|_| service.as_ref().unwrap().invitation());
    let a = Client::new();
    let b = Client::new();
    call(
        &a.app,
        json!({"action":"register","server_url":url,"username":"purgeintegration","password":"integration fixture password","device_name":"A","invite_code":invitation}),
    );
    b.login("purgeintegration", &url);
    let n = a.create("permanent deletion fixture");
    let id = n["id"].as_str().unwrap();
    a.sync();
    b.sync();
    let delete = |c: &Client| {
        let n = c.get(id);
        call(
            &c.app,
            json!({"action":"set_deleted","id":id,"expected_generation":n["generation"],"deleted":true}),
        )
    };
    let purge = |c: &Client| {
        let n = c.get(id);
        call(
            &c.app,
            json!({"action":"purge","id":id,"expected_generation":n["generation"]}),
        )
    };
    delete(&a);
    a.sync();
    b.sync();
    assert_eq!(purge(&a)["purge_state"], "pending");
    let a = a.restart();
    assert_eq!(a.get(id)["purge_state"], "pending");
    let bn = b.get(id);
    call(
        &b.app,
        json!({"action":"set_deleted","id":id,"expected_generation":bn["generation"],"deleted":false}),
    );
    b.sync();
    a.sync();
    assert_eq!(a.get(id)["purge_state"], "rejected");
    assert_eq!(a.get(id)["deleted"], false);
    // Queue ordinary deletion and purge together while hidden/offline; resolve in order.
    delete(&a);
    assert_eq!(purge(&a)["purge_state"], "pending");
    a.sync();
    assert_eq!(a.get(id)["purge_state"], "purged");
    // The other device submits an old edit after deletion; it must not become a conflict copy.
    b.save(id, "offline input from old device");
    b.sync();
    assert_eq!(b.get(id)["purge_state"], "purged");
    assert_eq!(b.get(id)["content"], "");
    assert!(b.list(false).is_empty());
    assert!(b.list(true).is_empty());
}
struct Temporary(PathBuf);
impl Temporary {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("djhx-core-integration-{}", uid()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temporary {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
#[derive(Clone, Default)]
struct Credentials {
    tokens: Arc<Mutex<HashMap<String, String>>>,
    ignore_remove: Arc<AtomicBool>,
}
impl CredentialStore for Credentials {
    fn load(&self, id: String) -> Option<String> {
        self.tokens.lock().unwrap().get(&id).cloned()
    }
    fn save(&self, id: String, token: String) -> bool {
        self.tokens.lock().unwrap().insert(id, token);
        true
    }
    fn remove(&self, id: String) {
        if !self.ignore_remove.load(Ordering::SeqCst) {
            self.tokens.lock().unwrap().remove(&id);
        }
    }
}
struct Service {
    binary: PathBuf,
    root: Temporary,
    url: String,
    port: u16,
    child: Option<Child>,
}
impl Service {
    fn new() -> Self {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let binary = PathBuf::from(
            std::env::var("DJHX_SERVER_BIN").expect("set DJHX_SERVER_BIN to the built Go service"),
        );
        assert!(
            binary.is_absolute() && binary.is_file(),
            "DJHX_SERVER_BIN must be an existing absolute path"
        );
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let mut service = Self {
            binary,
            root: Temporary::new(),
            url: format!("http://127.0.0.1:{port}"),
            port,
            child: None,
        };
        service.start();
        service
    }
    fn cli(&self, action: &str, extra: &[&str]) -> String {
        let result = Command::new(&self.binary)
            .arg(action)
            .arg("--db")
            .arg(self.root.0.join("db/memo.db"))
            .arg("--epoch-file")
            .arg(self.root.0.join("runtime/epoch"))
            .args(extra)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "administrative command {action} failed: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        String::from_utf8(result.stdout).unwrap()
    }
    fn invitation(&self) -> String {
        self.cli("invite-create", &[])
            .lines()
            .find_map(|s| s.strip_prefix("invite_code="))
            .unwrap()
            .to_owned()
    }
    fn start(&mut self) {
        let child = Command::new(&self.binary)
            .arg("serve")
            .arg("--dev-http")
            .arg("--listen")
            .arg(format!("127.0.0.1:{}", self.port))
            .arg("--db")
            .arg(self.root.0.join("db/memo.db"))
            .arg("--epoch-file")
            .arg(self.root.0.join("runtime/epoch"))
            .args(["--register-attempts", "1000", "--login-failures", "1000"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        self.child = Some(child);
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if reqwest::blocking::get(format!("{}/healthz", self.url))
                .is_ok_and(|r| r.status().is_success())
            {
                return;
            }
            assert!(
                self.child.as_mut().unwrap().try_wait().unwrap().is_none(),
                "Go service exited during startup"
            );
            thread::sleep(Duration::from_millis(20));
        }
        panic!("Go service did not become healthy");
    }
    fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
    fn backup(&self) -> PathBuf {
        let path = self.root.0.join(format!("snapshot-{}.db", uid()));
        self.cli("backup", &["--output", path.to_str().unwrap()]);
        path
    }
    fn restore(&mut self, snapshot: &Path) {
        self.stop();
        let dbdir = self.root.0.join("db");
        std::fs::rename(&dbdir, self.root.0.join(format!("preserved-{}", uid()))).unwrap();
        std::fs::create_dir_all(&dbdir).unwrap();
        std::fs::copy(snapshot, dbdir.join("memo.db")).unwrap();
        self.cli("epoch-rotate", &[]);
        self.start();
    }
}
impl Drop for Service {
    fn drop(&mut self) {
        self.stop();
    }
}
struct Client {
    root: Temporary,
    credentials: Credentials,
    app: Arc<MemoApp>,
}
impl Client {
    fn new() -> Self {
        let root = Temporary::new();
        let credentials = Credentials::default();
        let app = MemoApp::new(
            root.0.to_str().unwrap().into(),
            Box::new(credentials.clone()),
            true,
        )
        .unwrap();
        call(&app, json!({"action":"foreground","active":false}));
        Self {
            root,
            credentials,
            app,
        }
    }
    fn restart(self) -> Self {
        let Self {
            root,
            credentials,
            app,
        } = self;
        drop(app);
        let app = MemoApp::new(
            root.0.to_str().unwrap().into(),
            Box::new(credentials.clone()),
            true,
        )
        .unwrap();
        call(&app, json!({"action":"foreground","active":false}));
        Self {
            root,
            credentials,
            app,
        }
    }
    fn register(&self, service: &Service, name: &str, server: &str) {
        call(
            &self.app,
            json!({"action":"register","server_url":server,"username":name,"password":"integration fixture password","device_name":"Fixture A","invite_code":service.invitation()}),
        );
    }
    fn login(&self, name: &str, server: &str) {
        call(
            &self.app,
            json!({"action":"login","server_url":server,"username":name,"password":"integration fixture password","device_name":"Fixture B"}),
        );
    }
    fn state(&self) -> Value {
        call(&self.app, json!({"action":"state"}))
    }
    fn profile(&self) -> String {
        self.state()["profile"]["id"].as_str().unwrap().into()
    }
    fn token(&self) -> String {
        self.credentials.load(self.profile()).unwrap()
    }
    fn sync(&self) {
        let result = call(&self.app, json!({"action":"sync","wait":true}));
        assert!(result["error"].is_null(), "sync status error: {result}");
    }
    fn create(&self, content: &str) -> Value {
        call(
            &self.app,
            json!({"action":"create","profile_id":self.profile(),"request_id":uid(),"content":content}),
        )
    }
    fn get(&self, id: &str) -> Value {
        call(
            &self.app,
            json!({"action":"get","profile_id":self.profile(),"id":id}),
        )
    }
    fn save(&self, id: &str, content: &str) -> Value {
        let note = self.get(id);
        call(
            &self.app,
            json!({"action":"save","profile_id":self.profile(),"id":id,"expected_generation":note["generation"],"content":content}),
        )
    }
    fn list(&self, deleted: bool) -> Vec<Value> {
        call(
            &self.app,
            json!({"action":"list","profile_id":self.profile(),"deleted":deleted}),
        )
        .as_array()
        .unwrap()
        .clone()
    }
}
fn envelope(app: &MemoApp, request: Value) -> Value {
    serde_json::from_str(&app.invoke(request.to_string())).unwrap()
}
fn sync_error(result: &Value) -> &Value {
    let error = if result["ok"] == true {
        &result["data"]["error"]
    } else {
        &result["error"]
    };
    assert!(
        error["code"].is_string(),
        "synchronization unexpectedly succeeded: {result}"
    );
    error
}
fn call(app: &MemoApp, request: Value) -> Value {
    let action = request["action"].clone();
    let result = envelope(app, request);
    assert_eq!(
        result["ok"], true,
        "bridge action {action} failed: {result}"
    );
    result["data"].clone()
}
fn wire(server: &str, token: &str, method: &str, path: &str, body: Option<Value>) -> Value {
    let client = reqwest::blocking::Client::new();
    let request = client
        .request(method.parse().unwrap(), format!("{server}{path}"))
        .bearer_auth(token)
        .header("X-Memo-Protocol", "2");
    let response = if let Some(body) = body {
        request.json(&body)
    } else {
        request
    }
    .send()
    .unwrap();
    assert!(
        response.status().is_success(),
        "wire request {method} {path} failed ({})",
        response.status()
    );
    if response.status().as_u16() == 204 {
        Value::Null
    } else {
        response.json().unwrap()
    }
}

#[derive(Default)]
struct StatusGate {
    waiting: bool,
    released: bool,
}
#[derive(Default)]
struct ProxyControl {
    drop_push: AtomicBool,
    hold_status: AtomicBool,
    status_gate: Mutex<StatusGate>,
    status_cv: Condvar,
    captured: Mutex<Option<Value>>,
    captured_cv: Condvar,
}
struct LossProxy {
    url: String,
    stop: Arc<AtomicBool>,
    thread: Option<thread::JoinHandle<()>>,
    control: Arc<ProxyControl>,
}
impl LossProxy {
    fn new(upstream: String) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let stop = Arc::new(AtomicBool::new(false));
        let control = Arc::new(ProxyControl::default());
        let child_stop = stop.clone();
        let child_control = control.clone();
        let thread = thread::spawn(move || {
            while !child_stop.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let upstream = upstream.clone();
                        let control = child_control.clone();
                        thread::spawn(move || proxy_request(stream, &upstream, &control));
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            url,
            stop,
            thread: Some(thread),
            control,
        }
    }
    fn drop_next_push(&self) {
        *self.control.captured.lock().unwrap() = None;
        self.control.drop_push.store(true, Ordering::SeqCst);
    }
    fn hold_next_status(&self) {
        *self.control.status_gate.lock().unwrap() = StatusGate::default();
        self.control.hold_status.store(true, Ordering::SeqCst);
    }
    fn wait_for_held_status(&self) {
        let gate = self.control.status_gate.lock().unwrap();
        let (_gate, result) = self
            .control
            .status_cv
            .wait_timeout_while(gate, Duration::from_secs(5), |gate| !gate.waiting)
            .unwrap();
        assert!(
            !result.timed_out(),
            "proxy never intercepted the old credential status request"
        );
    }
    fn release_status(&self) {
        self.control.status_gate.lock().unwrap().released = true;
        self.control.status_cv.notify_all();
    }
    fn captured(&self) -> Value {
        let guard = self.control.captured.lock().unwrap();
        let (guard, result) = self
            .control
            .captured_cv
            .wait_timeout_while(guard, Duration::from_secs(5), |g| g.is_none())
            .unwrap();
        assert!(
            !result.timed_out(),
            "proxy did not capture committed Push response"
        );
        guard.clone().unwrap()
    }
}
impl Drop for LossProxy {
    fn drop(&mut self) {
        self.release_status();
        self.stop.store(true, Ordering::SeqCst);
        if let Some(thread) = self.thread.take() {
            thread.join().unwrap();
        }
    }
}
fn proxy_request(mut stream: TcpStream, upstream: &str, control: &ProxyControl) {
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let mut reader = BufReader::new(&mut stream);
    let mut first = String::new();
    if reader.read_line(&mut first).is_err() {
        return;
    }
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or("GET");
    let path = parts.next().unwrap_or("/");
    let mut headers = HashMap::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() {
            return;
        }
        if line == "\r\n" || line.is_empty() {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.to_ascii_lowercase(), v.trim().to_owned());
        }
    }
    let size = headers
        .get("content-length")
        .and_then(|n| n.parse::<usize>().ok())
        .unwrap_or(0);
    if size > 8 * 1024 * 1024 {
        return;
    }
    let mut body = vec![0; size];
    if reader.read_exact(&mut body).is_err() {
        return;
    }
    drop(reader);
    if method == "GET"
        && path == "/v1/sync/status"
        && control.hold_status.swap(false, Ordering::SeqCst)
    {
        let mut gate = control.status_gate.lock().unwrap();
        gate.waiting = true;
        control.status_cv.notify_all();
        let (_gate, result) = control
            .status_cv
            .wait_timeout_while(gate, Duration::from_secs(10), |gate| !gate.released)
            .unwrap();
        if result.timed_out() {
            return;
        }
    }
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap();
    let mut request = client.request(method.parse().unwrap(), format!("{upstream}{path}"));
    for key in ["authorization", "content-type", "accept"] {
        if let Some(value) = headers.get(key) {
            request = request.header(key, value);
        }
    }
    let Ok(response) = request.body(body).send() else {
        return;
    };
    let status = response.status();
    let content_type = response
        .headers()
        .get("content-type")
        .and_then(|s| s.to_str().ok())
        .unwrap_or("application/json")
        .to_owned();
    let Ok(bytes) = response.bytes() else {
        return;
    };
    if method == "POST"
        && path == "/v1/sync/push"
        && status.is_success()
        && control.drop_push.swap(false, Ordering::SeqCst)
    {
        *control.captured.lock().unwrap() = Some(serde_json::from_slice(&bytes).unwrap());
        control.captured_cv.notify_all();
        return;
    }
    let header = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        status.as_u16(),
        status.canonical_reason().unwrap_or("Response"),
        content_type,
        bytes.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(&bytes);
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; starts the actual Go service"]
fn shared_fixtures_over_real_http() {
    let service = Service::new();
    let suite: Value =
        serde_json::from_str(include_str!("../../spec/conformance/fixtures.json")).unwrap();
    assert_eq!(suite["schema_version"], 1);
    assert!(suite["cases"].as_array().unwrap().len() >= 11);
    for (index, case) in suite["cases"].as_array().unwrap().iter().enumerate() {
        let client = Client::new();
        let username = format!("fixture_{index}");
        client.register(&service, &username, &service.url);
        let token_a = client.token();
        let session_b:Value=reqwest::blocking::Client::new().post(format!("{}/v1/auth/login",service.url)).json(&json!({"username":username,"password":"integration fixture password","device_id":uid(),"device_name":"Fixture B"})).send().unwrap().json().unwrap();
        let token_b = session_b["token"].as_str().expect("fixture B login");
        let epoch = wire(&service.url, &token_a, "GET", "/v1/sync/status", None)["epoch"]
            .as_str()
            .unwrap()
            .to_owned();
        let mut symbols = HashMap::<String, String>::new();
        for initial in case["initial"].as_array().unwrap() {
            let note = symbols
                .entry(initial["note"].as_str().unwrap().into())
                .or_insert_with(uid)
                .clone();
            assert_eq!(
                initial["rev"], "1",
                "integration seeder expects initial revision one"
            );
            let result = wire(
                &service.url,
                &token_a,
                "POST",
                "/v1/sync/push",
                Some(
                    json!({"epoch":epoch,"ops":[{"op_id":uid(),"note_id":note,"base_rev":"0","content":initial["content"],"deleted":initial["deleted"]}]}),
                ),
            );
            assert_eq!(result["results"][0]["status"], "accepted");
        }
        for operation in case["ops"].as_array().unwrap() {
            let op = symbols
                .entry(operation["op"].as_str().unwrap().into())
                .or_insert_with(uid)
                .clone();
            let note = symbols
                .entry(operation["note"].as_str().unwrap().into())
                .or_insert_with(uid)
                .clone();
            let token = if operation["device"] == "B" {
                token_b
            } else {
                &token_a
            };
            let response = wire(
                &service.url,
                token,
                "POST",
                "/v1/sync/push",
                Some(
                    json!({"epoch":epoch,"ops":[{"op_id":op,"note_id":note,"base_rev":operation["base_rev"],"content":operation["content"],"deleted":operation["deleted"]}]}),
                ),
            );
            let result = &response["results"][0];
            let expected = &operation["expect"];
            assert_eq!(
                result["status"], expected["status"],
                "case {}",
                case["case_id"]
            );
            for key in ["rev", "seq", "conflict_rev", "conflict_seq"] {
                if !expected[key].is_null() {
                    assert_eq!(
                        result[key], expected[key],
                        "{} field {key}",
                        case["case_id"]
                    );
                }
            }
            if !expected["code"].is_null() {
                assert_eq!(result["error"]["code"], expected["code"]);
            }
            if let Some(alias) = expected["conflict_note"].as_str() {
                symbols.insert(
                    alias.into(),
                    result["conflict_note_id"].as_str().unwrap().into(),
                );
            }
        }
        client.sync();
        let active = client.list(false);
        let deleted = client.list(true);
        assert_eq!(
            active.len() + deleted.len(),
            case["expect"]["notes"].as_array().unwrap().len()
        );
        for expected in case["expect"]["notes"].as_array().unwrap() {
            let id = &symbols[expected["note"].as_str().unwrap()];
            let actual = client.get(id);
            assert_eq!(
                actual["content"], expected["content"],
                "{} note content",
                case["case_id"]
            );
            assert_eq!(actual["deleted"], expected["deleted"]);
            assert_eq!(actual["sync_state"], "synced");
        }
        let head = wire(&service.url, &token_a, "GET", "/v1/sync/status", None);
        assert_eq!(head["head_seq"], case["expect"]["head_seq"]);
    }
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; starts the actual Go service"]
fn offline_conflicts_response_loss_and_epoch_recovery() {
    let mut service = Service::new();
    let a = Client::new();
    let b = Client::new();
    a.register(&service, "integration_user", &service.url);
    b.login("integration_user", &service.url);
    let note = a.create("初始");
    let id = note["id"].as_str().unwrap();
    a.sync();
    b.sync();
    a.save(id, "甲");
    b.save(id, "乙");
    a.sync();
    b.sync();
    a.sync();
    assert_eq!(a.get(id)["content"], "甲");
    assert!(
        a.list(false)
            .iter()
            .any(|n| n["content"] == "乙" && n["conflict"]["reason"] == "stale_edit")
    );
    let original = b.get(id);
    call(
        &a.app,
        json!({"action":"set_deleted","profile_id":a.profile(),"id":id,"expected_generation":a.get(id)["generation"],"deleted":true}),
    );
    a.sync();
    call(
        &b.app,
        json!({"action":"save","profile_id":b.profile(),"id":id,"expected_generation":original["generation"],"content":"删除后的离线修改"}),
    );
    b.sync();
    assert!(b.get(id)["deleted"].as_bool().unwrap());
    assert!(
        b.list(false)
            .iter()
            .any(|n| n["content"] == "删除后的离线修改"
                && n["conflict"]["reason"] == "edit_after_delete")
    );
    // A server-committed response is dropped; the frozen operation survives restart.
    let proxy = LossProxy::new(service.url.clone());
    let mut c = Client::new();
    c.login("integration_user", &proxy.url);
    c.sync();
    let pending = c.create("响应丢失的新建");
    let pending_id = pending["id"].as_str().unwrap().to_owned();
    proxy.drop_next_push();
    let lost = envelope(&c.app, json!({"action":"sync","wait":true}));
    assert!(sync_error(&lost)["retryable"].as_bool().unwrap());
    let captured = proxy.captured();
    assert_eq!(captured["results"][0]["status"], "accepted");
    let old_op = captured["results"][0]["op_id"].clone();
    let profile = c.profile();
    let db = rusqlite::Connection::open(c.root.0.join(format!("{profile}.sqlite"))).unwrap();
    let frozen: String = db
        .query_row(
            "SELECT op_id FROM outbox WHERE note_id=?",
            [&pending_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(old_op, frozen);
    drop(db);
    c = c.restart();
    c.sync();
    assert_eq!(c.get(&pending_id)["content"], "响应丢失的新建");
    assert_eq!(c.get(&pending_id)["sync_state"], "synced");
    a.sync();
    assert_eq!(
        a.list(false)
            .iter()
            .filter(|n| n["id"] == pending_id)
            .count(),
        1
    );
    // Recovery retains old outbox and content as a read-only profile, then pulls a new file.
    let snapshot = service.backup();
    let old_profile = c.profile();
    let recovery_note = c.create("备份后尚未上传内容");
    let recovery_id = recovery_note["id"].as_str().unwrap().to_owned();
    service.restore(&snapshot);
    let recovered = envelope(&c.app, json!({"action":"sync","wait":true}));
    assert_eq!(sync_error(&recovered)["code"], "RECOVERY_REQUIRED");
    let state = c.state();
    let fresh_profile = state["profile"]["id"].as_str().unwrap().to_owned();
    assert_ne!(old_profile, fresh_profile);
    let old = state["profiles"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["id"] == old_profile)
        .unwrap();
    assert_eq!(old["kind"], "recovery");
    assert_eq!(old["read_only"], true);
    c.sync();
    assert!(!c.list(false).iter().any(|n| n["id"] == recovery_id));
    call(&c.app, json!({"action":"switch","profile_id":old_profile}));
    assert_eq!(c.get(&recovery_id)["content"], "备份后尚未上传内容");
    let rejected = envelope(
        &c.app,
        json!({"action":"save","profile_id":old_profile,"id":recovery_id,"expected_generation":recovery_note["generation"],"content":"不能覆盖"}),
    );
    assert_eq!(rejected["error"]["code"], "RECOVERY_REQUIRED");
    call(
        &c.app,
        json!({"action":"switch","profile_id":fresh_profile}),
    );
    let imported = call(
        &c.app,
        json!({"action":"import","profile_id":fresh_profile,"source_profile_id":old_profile,"ids":[recovery_id]}),
    );
    assert_eq!(imported["imported"], 1);
    c.sync();
    assert!(
        c.list(false)
            .iter()
            .any(|n| n["content"] == "备份后尚未上传内容" && n["id"] != recovery_id)
    );
    let repeated = call(
        &c.app,
        json!({"action":"import","profile_id":fresh_profile,"source_profile_id":old_profile,"ids":[recovery_id]}),
    );
    assert_eq!(repeated["imported"], 0);
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; regression for stale conflict acknowledgements"]
fn lost_conflict_acknowledgement_preserves_newer_remote_copy() {
    let service = Service::new();
    let a = Client::new();
    a.register(&service, "lost_conflict", &service.url);
    let note = a.create("原始");
    let id = note["id"].as_str().unwrap();
    a.sync();
    let proxy = LossProxy::new(service.url.clone());
    let mut b = Client::new();
    b.login("lost_conflict", &proxy.url);
    b.sync();
    b.save(id, "冲突传入正文");
    a.save(id, "权威更新");
    a.sync();
    proxy.drop_next_push();
    let lost = envelope(&b.app, json!({"action":"sync","wait":true}));
    assert!(sync_error(&lost)["retryable"].as_bool().unwrap());
    let captured = proxy.captured();
    assert_eq!(captured["results"][0]["status"], "conflict");
    let copy = captured["results"][0]["conflict_note_id"]
        .as_str()
        .unwrap()
        .to_owned();
    a.sync();
    a.save(&copy, "副本随后被远端修改");
    a.sync();
    b = b.restart();
    b.sync();
    assert_eq!(
        b.get(&copy)["content"],
        "副本随后被远端修改",
        "retrying the old conflict acknowledgement must not roll back a newer already-pulled snapshot"
    );
    assert_eq!(b.get(&copy)["sync_state"], "synced");
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; account isolation and revocation recovery"]
fn account_switch_revocation_and_failed_credential_removal() {
    let mut service = Service::new();
    let mut a = Client::new();
    let b = Client::new();
    let local = a.create("仅本机来源");
    let local_id = local["id"].as_str().unwrap();
    a.register(&service, "isolated_alice", &service.url);
    let alice_profile = a.profile();
    let alice_device = a.state()["profile"]["device_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let imported = call(
        &a.app,
        json!({"action":"import","profile_id":alice_profile,"ids":[local_id]}),
    );
    assert_eq!(imported["imported"], 1);
    let alice_note = a.create("Alice私有正文");
    let alice_id = alice_note["id"].as_str().unwrap().to_owned();
    a.sync();
    b.register(&service, "isolated_bob", &service.url);
    let bob_note = b.create("Bob私有正文");
    b.sync();
    a.login("isolated_bob", &service.url);
    let bob_profile = a.profile();
    a.sync();
    assert_eq!(a.list(false).len(), 1);
    assert_eq!(a.list(false)[0]["id"], bob_note["id"]);
    let old_task = envelope(
        &a.app,
        json!({"action":"save","profile_id":alice_profile,"id":alice_id,"expected_generation":alice_note["generation"],"content":"排队的Alice缓冲"}),
    );
    assert_eq!(old_task["error"]["code"], "PROFILE_CHANGED");
    assert!(
        a.list(false)
            .iter()
            .all(|n| n["content"] != "Alice私有正文")
    );
    call(
        &a.app,
        json!({"action":"switch","profile_id":alice_profile}),
    );
    assert_eq!(a.get(&alice_id)["content"], "Alice私有正文");
    let other = Client::new();
    other.login("isolated_alice", &service.url);
    other.sync();
    call(
        &other.app,
        json!({"action":"revoke_device","id":alice_device}),
    );
    a.save(&alice_id, "吊销后离线编辑");
    let rejected = envelope(&a.app, json!({"action":"sync","wait":true}));
    assert_eq!(sync_error(&rejected)["code"], "AUTH_REQUIRED");
    assert_eq!(a.get(&alice_id)["content"], "吊销后离线编辑");
    a.login("isolated_alice", &service.url);
    a.sync();
    other.sync();
    assert_eq!(other.get(&alice_id)["content"], "吊销后离线编辑");
    // A native credential service may silently fail removal. Persistent logout must win.
    a.credentials.ignore_remove.store(true, Ordering::SeqCst);
    service.stop();
    let logged_out = call(&a.app, json!({"action":"logout"}));
    assert_eq!(logged_out["remote_revoked"], false);
    a = a.restart();
    assert_eq!(a.profile(), "local");
    call(
        &a.app,
        json!({"action":"switch","profile_id":alice_profile}),
    );
    assert_eq!(a.state()["sync"]["state"], "auth_required");
    assert_eq!(a.get(&alice_id)["content"], "吊销后离线编辑");
    call(&a.app, json!({"action":"switch","profile_id":bob_profile}));
    assert_eq!(
        a.get(bob_note["id"].as_str().unwrap())["content"],
        "Bob私有正文"
    );
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; delayed old credential 401 cannot revoke new login"]
fn delayed_old_401_preserves_reauthenticated_session() {
    let service = Service::new();
    let proxy = LossProxy::new(service.url.clone());
    let client = Client::new();
    client.register(&service, "reauthentication_race", &proxy.url);
    client.sync();
    let original_token = client.token();
    proxy.hold_next_status();
    let app = client.app.clone();
    let waiting = thread::spawn(move || envelope(&app, json!({"action":"sync","wait":true})));
    proxy.wait_for_held_status();
    // Reauthorization replaces the same device token while an old request is in flight.
    // The gate only releases after the login bridge has installed its new credential.
    client.login("reauthentication_race", &proxy.url);
    let fresh_token = client.token();
    assert_ne!(original_token, fresh_token);
    proxy.release_status();
    let old_response = waiting.join().unwrap();
    assert_eq!(
        sync_error(&old_response)["code"],
        "SESSION_CHANGED",
        "the delayed old request must observe actual server rejection without invalidating its replacement"
    );
    assert_eq!(
        client.credentials.load(client.profile()),
        Some(fresh_token),
        "a late 401 for an obsolete token removed the newly authorized credential"
    );
    client.sync();
    assert_ne!(client.state()["sync"]["state"], "auth_required");
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; tray-style manual sync while the window is hidden"]
fn hidden_manual_sync_uploads_queued_content() {
    let service = Service::new();
    let client = Client::new(); // The real coordinator is running with foreground=false.
    client.register(&service, "hidden_manual_sync", &service.url);
    let note = client.create("隐藏窗口时显式同步");
    let token = client.token();
    call(&client.app, json!({"action":"sync","wait":false}));
    // Observe the committed server state, rather than guessing coordinator ordering.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let status = wire(&service.url, &token, "GET", "/v1/sync/status", None);
        if status["head_seq"] == "1" {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "an explicit hidden-window sync was never executed"
        );
        thread::sleep(Duration::from_millis(20));
    }
    let server_status = wire(&service.url, &token, "GET", "/v1/sync/status", None);
    let epoch = server_status["epoch"].as_str().unwrap();
    let page = wire(
        &service.url,
        &token,
        "GET",
        &format!("/v1/sync/pull?epoch={epoch}&cursor=0"),
        None,
    );
    assert_eq!(page["changes"].as_array().unwrap().len(), 1);
    assert_eq!(page["changes"][0]["id"], note["id"]);
    assert_eq!(page["changes"][0]["content"], "隐藏窗口时显式同步");
}

#[test]
#[ignore = "requires DJHX_SERVER_BIN; measures foreground core coordinator and SSE latency"]
fn foreground_automatic_sse_converges_within_latency_target() {
    let service = Service::new();
    let a = Client::new();
    let b = Client::new();
    a.register(&service, "automatic_sse_latency", &service.url);
    b.login("automatic_sse_latency", &service.url);
    let initial = a.create("Automatic coordinator latency fixture");
    let id = initial["id"].as_str().unwrap();
    a.sync();
    b.sync();
    let b_profile = b.profile();
    call(&a.app, json!({"action":"foreground","active":true}));
    call(&b.app, json!({"action":"foreground","active":true}));
    let mut samples = Vec::with_capacity(30);
    for sample in 0..30 {
        let content = format!("fixture {sample}\n{}", "a".repeat(65_480));
        assert!(content.len() <= 65_536);
        a.save(id, &content); // Measures from a real acknowledged local transaction.
        let saved_at = Instant::now();
        loop {
            let visible = call(
                &b.app,
                json!({"action":"get","profile_id":b_profile,"id":id}),
            );
            if visible["content"].as_str() == Some(content.as_str()) {
                samples.push(saved_at.elapsed());
                break;
            }
            assert!(
                saved_at.elapsed() < Duration::from_secs(10),
                "automatic convergence timed out at numeric sample {sample}"
            );
            thread::sleep(Duration::from_millis(20));
        }
    }
    call(&a.app, json!({"action":"foreground","active":false}));
    call(&b.app, json!({"action":"foreground","active":false}));
    samples.sort_unstable();
    println!(
        "core_auto_sse_sorted_ms={:?}",
        samples
            .iter()
            .map(|duration| (duration.as_secs_f64() * 1_000_000.0).round() / 1000.0)
            .collect::<Vec<_>>()
    );
    let p95 = samples[28]; // nearest-rank ceil(30 * .95) = 29
    let maximum = samples[29];
    println!(
        "core_auto_sse_samples=30 p95_ms={:.3} max_ms={:.3}",
        p95.as_secs_f64() * 1000.0,
        maximum.as_secs_f64() * 1000.0
    );
    assert!(
        p95 <= Duration::from_secs(5),
        "core automatic SSE P95 exceeded 5000ms"
    );
}
