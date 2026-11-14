use super::*;
use crate::{CredentialStore, model::Profile};
use serde_json::json;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

#[derive(Default)]
struct Credentials(AtomicUsize);
impl CredentialStore for Credentials {
    fn load(&self, _: String) -> Option<String> {
        self.0.fetch_add(1, Ordering::SeqCst);
        None
    }
    fn save(&self, _: String, _: String) -> bool {
        false
    }
    fn remove(&self, _: String) {}
}

fn account_app(url: &str, cached: bool) -> (Arc<MemoApp>, Arc<Credentials>, String) {
    let credentials = Arc::new(Credentials::default());
    let engine = Engine::open(
        std::env::temp_dir().join(format!("memo-sse-barrier-{}", uuid::Uuid::new_v4())),
        credentials.clone(),
        true,
    )
    .unwrap();
    let profile_id = uuid::Uuid::new_v4().to_string();
    {
        let mut registry = engine.registry.lock().unwrap();
        registry.selected = profile_id.clone();
        registry.profiles.push(Profile {
            id: profile_id.clone(),
            kind: "account".into(),
            label: "SSE barrier fixture".into(),
            server_url: Some(url.into()),
            user_id: Some(uuid::Uuid::new_v4().to_string()),
            username: Some("fixture".into()),
            device_id: Some(uuid::Uuid::new_v4().to_string()),
            read_only: false,
        });
    }
    if cached {
        engine
            .tokens
            .lock()
            .unwrap()
            .insert(profile_id.clone(), "old-fixture-session".into());
    }
    // Isolate the real SSE worker from the separate pull/push scheduler.
    engine
        .auto_blocked
        .lock()
        .unwrap()
        .insert(profile_id.clone());
    (
        Arc::new(MemoApp {
            inner: Arc::new(engine),
        }),
        credentials,
        profile_id,
    )
}

fn wait_for(condition: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !condition() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(condition(), "SSE fixture did not reach the expected stage");
}

/// Real HTTP stream stays open, so a passing pause cannot depend on its EOF/timeout.
struct Stream {
    url: String,
    connected: Arc<AtomicBool>,
    respond: Arc<AtomicBool>,
    signal: Arc<AtomicBool>,
    beats: Arc<AtomicUsize>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Stream {
    fn new(unauthorized: bool) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let mut fixture = Self {
            url: format!("http://{}", listener.local_addr().unwrap()),
            connected: Arc::default(),
            respond: Arc::new(AtomicBool::new(!unauthorized)),
            signal: Arc::default(),
            beats: Arc::default(),
            stop: Arc::default(),
            worker: None,
        };
        let (connected, respond, signal, beats, stop) = (
            fixture.connected.clone(),
            fixture.respond.clone(),
            fixture.signal.clone(),
            fixture.beats.clone(),
            fixture.stop.clone(),
        );
        fixture.worker = Some(std::thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                let Ok((mut stream, _)) = listener.accept() else {
                    std::thread::sleep(Duration::from_millis(5));
                    continue;
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                stream
                    .set_write_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                let mut header = Vec::new();
                let mut byte = [0];
                while header.len() < 8192 && !header.ends_with(b"\r\n\r\n") {
                    if stream.read_exact(&mut byte).is_err() {
                        return;
                    }
                    header.push(byte[0]);
                }
                assert!(header.starts_with(b"GET /v1/events "));
                connected.store(true, Ordering::SeqCst);
                while !respond.load(Ordering::SeqCst) && !stop.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_millis(5));
                }
                if unauthorized {
                    let _ = stream.write_all(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    return;
                }
                if stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n").is_err() { return; }
                while !stop.load(Ordering::SeqCst) {
                    let body: &[u8] = if signal.swap(false, Ordering::SeqCst) {
                        b"data: {\"epoch\":\"fixture-epoch\",\"head_seq\":\"999\"}\n\n"
                    } else {
                        b": heartbeat\n\n"
                    };
                    if stream.write_all(body).is_err() {
                        return;
                    }
                    beats.fetch_add(1, Ordering::SeqCst);
                    std::thread::sleep(Duration::from_millis(30));
                }
                return;
            }
        }));
        fixture
    }
    fn close(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(worker) = self.worker.take() {
            worker.join().unwrap();
        }
    }
}
impl Drop for Stream {
    fn drop(&mut self) {
        self.close();
    }
}

#[test]
fn healthy_sse_does_not_block_install_or_apply_signals_while_paused() {
    let mut stream = Stream::new(false);
    let (app, _, _) = account_app(&stream.url, true);
    let note = app.inner.dispatch(&json!({"action":"create", "request_id":uuid::Uuid::new_v4().to_string(), "content":"SSE更新前的合成笔记😀"})).unwrap();
    app.inner.requested.lock().unwrap().clear();
    Engine::start(&app.inner);
    wait_for(|| stream.beats.load(Ordering::SeqCst) >= 4);
    let original_event = app.inner.event.load(Ordering::SeqCst);
    let (tx, rx) = std::sync::mpsc::channel();
    let installer = app.clone();
    let worker = std::thread::spawn(move || tx.send(installer.pause_for_update()).unwrap());
    let timely = rx.recv_timeout(Duration::from_secs(2));
    // Close a regressed stream before joining: a failure must not stall the suite for 40s.
    if timely.is_err() {
        stream.close();
    }
    worker.join().unwrap();
    timely
        .expect("Live SSE prevented installation checkpoint")
        .unwrap();
    stream.signal.store(true, Ordering::SeqCst);
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(app.inner.event.load(Ordering::SeqCst), original_event);
    assert!(app.inner.requested.lock().unwrap().is_empty());
    assert_eq!(
        app.inner
            .dispatch(&json!({"action":"state"}))
            .unwrap_err()
            .code,
        "UPDATE_BUSY"
    );
    app.resume_after_update();
    let saved = app
        .inner
        .dispatch(&json!({"action":"get", "id":note["id"]}))
        .unwrap();
    assert_eq!(saved["content"], note["content"]);
    assert_eq!(saved["generation"], note["generation"]);
}

#[test]
fn old_sse_signal_cannot_schedule_work_for_a_new_session() {
    let stream = Stream::new(false);
    let (app, _, profile) = account_app(&stream.url, true);
    Engine::start(&app.inner);
    wait_for(|| stream.beats.load(Ordering::SeqCst) >= 4);
    app.inner
        .tokens
        .lock()
        .unwrap()
        .insert(profile, "new-fixture-session".into());
    let original_event = app.inner.event.load(Ordering::SeqCst);
    stream.signal.store(true, Ordering::SeqCst);
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(app.inner.event.load(Ordering::SeqCst), original_event);
    assert!(app.inner.requested.lock().unwrap().is_empty());
}

#[test]
fn paused_background_workers_do_not_load_platform_credentials() {
    let (app, credentials, _) = account_app("http://127.0.0.1:1", false);
    app.pause_for_update().unwrap();
    Engine::start(&app.inner);
    std::thread::sleep(Duration::from_millis(1250));
    assert_eq!(credentials.0.load(Ordering::SeqCst), 0);
    app.resume_after_update();
    wait_for(|| credentials.0.load(Ordering::SeqCst) == 1);
}

#[test]
fn late_sse_unauthorized_cannot_clear_new_session_even_during_pause() {
    for (paused, replaced) in [(false, true), (true, true), (true, false)] {
        let stream = Stream::new(true);
        let (app, _, profile) = account_app(&stream.url, true);
        Engine::start(&app.inner);
        wait_for(|| stream.connected.load(Ordering::SeqCst));
        let expected = if replaced {
            "new-fixture-session"
        } else {
            "old-fixture-session"
        };
        app.inner
            .tokens
            .lock()
            .unwrap()
            .insert(profile.clone(), expected.into());
        let original_event = app.inner.event.load(Ordering::SeqCst);
        if paused {
            app.pause_for_update().unwrap();
        }
        stream.respond.store(true, Ordering::SeqCst);
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(
            app.inner.tokens.lock().unwrap().get(&profile).unwrap(),
            expected
        );
        assert_eq!(app.inner.event.load(Ordering::SeqCst), original_event);
        assert!(
            !app.inner
                .registry
                .lock()
                .unwrap()
                .logged_out
                .contains(&profile)
        );
        app.resume_after_update();
    }
}
