use super::*;
use sha2::Digest;
use std::io::Read;

#[derive(Default)]
struct MemoryCredentials(Mutex<HashMap<String, String>>);
impl CredentialStore for MemoryCredentials {
    fn load(&self, id: String) -> Option<String> {
        self.0.lock().unwrap().get(&id).cloned()
    }
    fn save(&self, id: String, token: String) -> bool {
        self.0.lock().unwrap().insert(id, token);
        true
    }
    fn remove(&self, id: String) {
        self.0.lock().unwrap().remove(&id);
    }
}

fn engine() -> Engine {
    Engine::open(
        std::env::temp_dir().join(format!("djhx-core-test-{}", id())),
        Arc::new(MemoryCredentials::default()),
        true,
    )
    .unwrap()
}
fn invoke(engine: &Engine, request: Value) -> Value {
    engine.dispatch(&request).unwrap()
}
fn account() -> Profile {
    Profile {
        id: id(),
        kind: "account".into(),
        label: "测试账号".into(),
        server_url: Some("http://127.0.0.1:1".into()),
        user_id: Some(id()),
        username: Some("tester".into()),
        device_id: Some(id()),
        read_only: false,
    }
}
fn wire(note_id: &str, content: &str, rev: i64, seq: i64) -> WireNote {
    WireNote {
        purged: false,
        id: note_id.into(),
        content: content.into(),
        deleted: false,
        rev: rev.to_string(),
        seq: seq.to_string(),
        created_at: now(),
        updated_at: now(),
        conflict: None,
    }
}
fn confirmation(op: &Operation, mut value: Value) -> Value {
    value["op_id"] = json!(op.op_id);
    value["note_id"] = json!(op.note_id);
    value
}
fn seed(db: &mut Connection, note_id: &str) -> LocalNote {
    db::apply_page(db, &[wire(note_id, "初始", 1, 1)], "1", "1", false).unwrap();
    db::note(db, note_id).unwrap()
}
fn edit(db: &Connection, note_id: &str, content: &str) {
    let mut n = db::note(db, note_id).unwrap();
    n.generation += 1;
    n.content = content.into();
    db::put(db, &n).unwrap();
}

#[test]
fn instance_lock_and_credential_session_replacement_are_safe() {
    let app = engine();
    assert!(
        Engine::open(
            app.root.clone(),
            Arc::new(MemoryCredentials::default()),
            true
        )
        .is_err()
    );
    let profile = account();
    app.tokens
        .lock()
        .unwrap()
        .insert(profile.id.clone(), "old-session".into());
    app.tokens
        .lock()
        .unwrap()
        .insert(profile.id.clone(), "new-session".into());
    assert!(
        !app.forget_token_if_current(&profile, Some("old-session"))
            .unwrap()
    );
    assert_eq!(app.token(&profile).unwrap(), "new-session");
    assert!(
        app.forget_token_if_current(&profile, Some("new-session"))
            .unwrap()
    );
    assert_eq!(app.token(&profile).unwrap_err().code, "AUTH_REQUIRED");
    assert!(
        app.registry
            .lock()
            .unwrap()
            .logged_out
            .contains(&profile.id)
    );
}

fn status_server(
    body: Value,
    status: &str,
    headers: &str,
    requests: usize,
) -> (String, std::thread::JoinHandle<()>) {
    use std::net::TcpListener;
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = format!("http://{}", listener.local_addr().unwrap());
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.to_string().len()
    );
    let handle = std::thread::spawn(move || {
        for stream in listener.incoming().take(requests) {
            let mut stream = stream.unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut received = Vec::new();
            let mut byte = [0];
            while !received.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                received.push(byte[0]);
            }
            stream.write_all(response.as_bytes()).unwrap();
        }
    });
    (address, handle)
}

fn install_account(app: &Engine, server: String) -> Profile {
    let mut profile = account();
    profile.server_url = Some(server);
    app.registry.lock().unwrap().profiles.push(profile.clone());
    app.registry.lock().unwrap().selected = profile.id.clone();
    app.tokens
        .lock()
        .unwrap()
        .insert(profile.id.clone(), "test-session".into());
    profile
}

#[test]
fn recovery_storage_failure_releases_sync_and_preserves_registry() {
    let app = engine();
    let epoch = id();
    let (server, handle) = status_server(
        json!({"protocol_version":2,"epoch":epoch,"head_seq":"0"}),
        "200 OK",
        "",
        2,
    );
    let profile = install_account(&app, server);
    let db = db::open(&app.path(&profile)).unwrap();
    db::set_state(&db, "epoch", &id()).unwrap();
    std::fs::remove_file(app.root.join("profiles.json")).unwrap();
    std::fs::create_dir(app.root.join("profiles.json")).unwrap();
    for _ in 0..2 {
        assert_eq!(
            app.synchronize(&profile).unwrap_err().code,
            "LOCAL_STORAGE_FAILED"
        );
        assert!(app.running.lock().unwrap().is_empty());
        assert_eq!(app.fixed_profile(&profile.id).unwrap().kind, "account");
    }
    handle.join().unwrap();
}

#[test]
fn retry_after_blocks_requests_and_wait_returns_sync_info() {
    let app = engine();
    let (server, handle) = status_server(
        json!({"error":{"code":"RATE_LIMITED","retryable":true}}),
        "429 Too Many Requests",
        "Retry-After: 60\r\n",
        1,
    );
    let profile = install_account(&app, server);
    let result = invoke(
        &app,
        json!({"action":"sync","wait":true,"profile_id":profile.id}),
    );
    assert_eq!(result["state"], "offline");
    assert_eq!(result["error"]["code"], "RATE_LIMITED");
    handle.join().unwrap();
    // The server is gone. A request would now produce NETWORK_UNAVAILABLE.
    assert_eq!(app.synchronize(&profile).unwrap_err().code, "RATE_LIMITED");
}

#[test]
fn explicit_sync_wakes_the_coordinator_with_a_hidden_window() {
    let app = Arc::new(engine());
    let (server, handle) = status_server(
        json!({"protocol_version":2,"epoch":id(),"head_seq":"0"}),
        "200 OK",
        "",
        1,
    );
    let profile = install_account(&app, server);
    app.foreground.store(false, Ordering::SeqCst);
    Engine::start(&app);
    invoke(&app, json!({"action":"sync","profile_id":profile.id}));
    let deadline = Instant::now() + Duration::from_secs(3);
    while !handle.is_finished() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(
        handle.is_finished(),
        "explicit tray sync must send the status request while hidden"
    );
    handle.join().unwrap();
}

#[test]
fn malformed_confirmation_keeps_the_frozen_operation() {
    let mut db = db::open(&engine().root.join("malformed.sqlite")).unwrap();
    let note_id = id();
    seed(&mut db, &note_id);
    edit(&db, &note_id, "待确认内容");
    let (op, generation) = db::freeze(&mut db).unwrap().unwrap();
    for result in [
        confirmation(&op, json!({"status":"accepted"})),
        json!({"op_id":id(),"note_id":note_id,"status":"accepted","rev":"2","seq":"2"}),
    ] {
        assert!(db::acknowledge(&mut db, &op, generation, &result, &account()).is_err());
        let (retry, retry_generation) = db::freeze(&mut db).unwrap().unwrap();
        assert_eq!(retry.op_id, op.op_id);
        assert_eq!(retry_generation, generation);
        assert_eq!(db::note(&db, &note_id).unwrap().content, "待确认内容");
    }
}

#[test]
fn replay_of_an_acknowledged_revision_keeps_the_ui_generation_valid() {
    let app = engine();
    let profile = install_account(&app, "http://127.0.0.1:1".into());
    let created = invoke(
        &app,
        json!({"action":"create","content":"首段","request_id":id(),"profile_id":profile.id}),
    );
    let mut database = db::open(&app.path(&profile)).unwrap();
    let (op, generation) = db::freeze(&mut database).unwrap().unwrap();
    db::acknowledge(
        &mut database,
        &op,
        generation,
        &confirmation(&op, json!({"status":"accepted","rev":"1","seq":"1"})),
        &profile,
    )
    .unwrap();
    let mut acknowledged = wire(&op.note_id, &op.content, 1, 1);
    acknowledged.created_at = "2020-01-01T00:00:00.000Z".into();
    db::apply_page(&mut database, &[acknowledged.clone()], "1", "1", false).unwrap();
    assert_eq!(
        db::note(&database, &op.note_id).unwrap().generation,
        generation
    );
    assert_eq!(
        db::note(&database, &op.note_id).unwrap().created_at,
        acknowledged.created_at
    );
    let saved = invoke(
        &app,
        json!({"action":"save","profile_id":profile.id,"id":created["id"],"expected_generation":created["generation"],"content":"首段续写"}),
    );
    assert_eq!(saved["content"], "首段续写");
    let (next, _) = db::freeze(&mut database).unwrap().unwrap();
    assert_eq!(next.base_rev, "1");
    assert_eq!(next.content, "首段续写");
    assert_eq!(
        db::apply_page(
            &mut database,
            &[wire(&op.note_id, "同版本错误正文", 1, 2)],
            "2",
            "2",
            false
        )
        .unwrap_err()
        .code,
        "INVALID_REQUEST"
    );
    assert_eq!(
        db::get_state(&database, "cursor").unwrap().as_deref(),
        Some("1")
    );
    assert_eq!(
        db::note(&database, &op.note_id).unwrap().content,
        "首段续写"
    );
    assert_eq!(
        db::freeze(&mut database).unwrap().unwrap().0.op_id,
        next.op_id
    );
}

#[test]
fn local_creation_is_idempotent_lf_and_generation_checked() {
    let app = engine();
    let request = id();
    let created = invoke(
        &app,
        json!({"action":"create","content":"# 会议\r\n\r\n内容🙂","request_id":request}),
    );
    assert_eq!(created["content"], "# 会议\n\n内容🙂");
    assert_eq!(created["title"], "会议");
    let repeated = invoke(
        &app,
        json!({"action":"create","content":"重试不应再创建","request_id":request}),
    );
    assert_eq!(created["id"], repeated["id"]);
    let saved = invoke(
        &app,
        json!({"action":"save","id":created["id"],"expected_generation":"1","content":"新版"}),
    );
    assert_eq!(saved["generation"], "2");
    let error=app.dispatch(&json!({"action":"save","id":created["id"],"expected_generation":"1","content":"旧缓冲"})).unwrap_err();
    assert_eq!(error.code, "LOCAL_GENERATION_CONFLICT");
    let root = app.root.clone();
    drop(app);
    let reloaded = Engine::open(root, Arc::new(MemoryCredentials::default()), true).unwrap();
    assert_eq!(
        invoke(&reloaded, json!({"action":"get","id":created["id"]}))["content"],
        "新版"
    );
}

#[test]
fn search_is_literal_short_chinese_ascii_and_tombstones_are_preserved() {
    let app = engine();
    let n = invoke(
        &app,
        json!({"action":"create","content":"会议 ABC 100% _ \" OR *","request_id":id()}),
    );
    for query in ["会", "会议", "abc", "%", "_", "\" OR"] {
        assert_eq!(
            invoke(&app, json!({"action":"list","query":query}))
                .as_array()
                .unwrap()
                .len(),
            1
        );
    }
    invoke(
        &app,
        json!({"action":"set_deleted","id":n["id"],"expected_generation":"1","deleted":true}),
    );
    assert!(
        invoke(&app, json!({"action":"list"}))
            .as_array()
            .unwrap()
            .is_empty()
    );
    let trash = invoke(&app, json!({"action":"list","deleted":true}));
    assert_eq!(trash[0]["content"], "会议 ABC 100% _ \" OR *");
    invoke(
        &app,
        json!({"action":"set_deleted","id":n["id"],"expected_generation":"2","deleted":false}),
    );
    assert_eq!(
        invoke(&app, json!({"action":"list"}))
            .as_array()
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn tasks_modify_only_the_marker_and_skip_all_code_blocks() {
    let source = "- [ ] 中文\n```md\n- [ ] 围栏\n```\n\n    - [ ] 缩进代码\n\n- [x] 完成\n";
    assert_eq!(
        toggle_task(source, 0).unwrap(),
        source.replacen("[ ]", "[x]", 1)
    );
    assert!(toggle_task(source, 2).is_err());
    assert!(toggle_task(source, 5).is_err());
    assert!(toggle_task(source, 7).unwrap().contains("- [ ] 完成"));
}

#[test]
fn pull_preserves_stale_edit_base_and_transaction_rolls_back_with_cursor() {
    let app = engine();
    let mut db = db::open(&app.root.join("isolation.sqlite")).unwrap();
    let n = id();
    seed(&mut db, &n);
    edit(&db, &n, "离线修改");
    db::apply_page(&mut db, &[wire(&n, "远端新版", 2, 2)], "2", "2", false).unwrap();
    assert_eq!(db::note(&db, &n).unwrap().content, "离线修改");
    assert_eq!(db::note(&db, &n).unwrap().base_rev, 1);
    assert!(db::apply_page(&mut db, &[wire(&n, "再次更新", 3, 3)], "3", "3", true).is_err());
    assert_eq!(db::get_state(&db, "cursor").unwrap().unwrap(), "2");
    assert_eq!(db::shadow(&db, &n).unwrap().unwrap().rev, "2");
    let (op, _) = db::freeze(&mut db).unwrap().unwrap();
    assert_eq!(op.base_rev, "1");
}

#[test]
fn accepted_old_generation_cannot_clear_new_input_and_freeze_is_immutable() {
    let app = engine();
    let profile = account();
    let mut db = db::open(&app.path(&profile)).unwrap();
    let n = id();
    seed(&mut db, &n);
    edit(&db, &n, "甲");
    let (op, g) = db::freeze(&mut db).unwrap().unwrap();
    edit(&db, &n, "甲乙");
    let (retry, retry_g) = db::freeze(&mut db).unwrap().unwrap();
    assert_eq!(retry.op_id, op.op_id);
    assert_eq!(retry.content, "甲");
    assert_eq!(retry_g, g);
    db::acknowledge(
        &mut db,
        &op,
        g,
        &confirmation(&op, json!({"status":"accepted","rev":"2","seq":"2"})),
        &profile,
    )
    .unwrap();
    let latest = db::note(&db, &n).unwrap();
    assert_eq!(latest.content, "甲乙");
    assert!(latest.generation > latest.acknowledged);
    let (next, _) = db::freeze(&mut db).unwrap().unwrap();
    assert_eq!(next.content, "甲乙");
    assert_eq!(next.base_rev, "2");
    assert_ne!(next.op_id, op.op_id);
}

#[test]
fn conflict_moves_continued_input_and_unknown_result_keeps_newer_copy() {
    for continued in [false, true] {
        let app = engine();
        let profile = account();
        let mut db = db::open(&app.path(&profile)).unwrap();
        let n = id();
        let copy = id();
        seed(&mut db, &n);
        edit(&db, &n, "原发送");
        let (op, g) = db::freeze(&mut db).unwrap().unwrap();
        if continued {
            edit(&db, &n, "发送期间继续输入");
        }
        db::apply_page(
            &mut db,
            &[
                wire(&n, "对方更新", 2, 2),
                wire(&copy, "副本被远端再次修改", 2, 4),
            ],
            "4",
            "4",
            false,
        )
        .unwrap();
        db::acknowledge(&mut db,&op,g,&confirmation(&op,json!({"status":"conflict","rev":"2","conflict_note_id":copy,"conflict_rev":"1","conflict_seq":"3"})),&profile).unwrap();
        assert_eq!(db::note(&db, &n).unwrap().content, "对方更新");
        let copy = db::note(&db, &copy).unwrap();
        if continued {
            assert_eq!(copy.content, "发送期间继续输入");
            assert_eq!(copy.base_rev, 1);
            assert!(copy.generation > copy.acknowledged);
        } else {
            assert_eq!(copy.content, "副本被远端再次修改");
            assert_eq!(copy.base_rev, 2);
            assert_eq!(copy.generation, copy.acknowledged);
        }
    }
}

#[test]
fn conflict_confirmation_preserves_two_independent_pending_branches() {
    let app = engine();
    let p = account();
    let mut db = db::open(&app.path(&p)).unwrap();
    let n = id();
    let copy = id();
    seed(&mut db, &n);
    edit(&db, &n, "原发送");
    let (op, g) = db::freeze(&mut db).unwrap().unwrap();
    edit(&db, &n, "原笔记继续输入");
    db::apply_page(
        &mut db,
        &[wire(&n, "权威原文", 2, 2), wire(&copy, "已知副本", 1, 3)],
        "3",
        "3",
        false,
    )
    .unwrap();
    edit(&db, &copy, "副本另一次本地编辑");
    db::acknowledge(&mut db,&op,g,&confirmation(&op,json!({"status":"conflict","rev":"2","conflict_note_id":copy,"conflict_rev":"1","conflict_seq":"3"})),&p).unwrap();
    assert_eq!(db::note(&db, &copy).unwrap().content, "副本另一次本地编辑");
    let notes = db::list(&db, "", false).unwrap();
    assert_eq!(notes.len(), 3);
    assert!(notes.iter().any(|n| n.content == "原笔记继续输入"));
}

#[test]
fn local_import_is_account_scoped_and_generation_idempotent() {
    let app = engine();
    invoke(
        &app,
        json!({"action":"create","content":"本机来源","request_id":id()}),
    );
    let p = account();
    {
        let mut registry = app.registry.lock().unwrap();
        registry.profiles.push(p.clone());
        registry.selected = p.id.clone();
    }
    assert_eq!(
        invoke(&app, json!({"action":"import","profile_id":p.id}))["imported"],
        1
    );
    assert_eq!(
        invoke(&app, json!({"action":"import","profile_id":p.id}))["imported"],
        0
    );
    let other = account();
    {
        let mut registry = app.registry.lock().unwrap();
        registry.profiles.push(other.clone());
        registry.selected = other.id.clone();
    }
    assert_eq!(
        invoke(&app, json!({"action":"import","profile_id":other.id}))["imported"],
        1
    );
    assert_eq!(
        app.dispatch(&json!({"action":"list","profile_id":p.id}))
            .unwrap_err()
            .code,
        "PROFILE_CHANGED"
    );
    let local = db::open(&app.root.join("local.sqlite")).unwrap();
    assert_eq!(db::list(&local, "", false).unwrap().len(), 1);
}

#[test]
fn zip_export_has_exact_manifest_and_no_path_or_secrets() {
    let app = engine();
    let note = invoke(
        &app,
        json!({"action":"create","content":"# 标题\n正文🙂","request_id":id()}),
    );
    let output = invoke(&app, json!({"action":"export"}));
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(output["bytes_base64"].as_str().unwrap())
        .unwrap();
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
    assert_eq!(zip.len(), 2);
    let mut content = String::new();
    zip.by_name(&format!("{}.md", note["id"].as_str().unwrap()))
        .unwrap()
        .read_to_string(&mut content)
        .unwrap();
    assert_eq!(content, "# 标题\n正文🙂");
    let manifest: Value = serde_json::from_reader(zip.by_name("manifest.json").unwrap()).unwrap();
    assert_eq!(manifest["source_kind"], "local");
    assert_eq!(manifest["notes"][0]["title"], "标题");
    assert!(manifest.get("token").is_none());
}

#[test]
fn trash_export_contains_only_tombstones() {
    let app = engine();
    let active = invoke(
        &app,
        json!({"action":"create","content":"保留活跃","request_id":id()}),
    );
    let trashed = invoke(
        &app,
        json!({"action":"create","content":"回收站正文","request_id":id()}),
    );
    invoke(
        &app,
        json!({"action":"set_deleted","id":trashed["id"],"expected_generation":trashed["generation"],"deleted":true}),
    );
    let output = invoke(
        &app,
        json!({"action":"export","include_deleted":true,"deleted_only":true}),
    );
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(output["bytes_base64"].as_str().unwrap())
        .unwrap();
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
    let manifest: Value = serde_json::from_reader(zip.by_name("manifest.json").unwrap()).unwrap();
    assert_eq!(manifest["notes"].as_array().unwrap().len(), 1);
    assert_eq!(manifest["notes"][0]["id"], trashed["id"]);
    assert_eq!(manifest["notes"][0]["deleted"], true);
    assert!(
        zip.by_name(&format!("{}.md", active["id"].as_str().unwrap()))
            .is_err()
    );
}

#[test]
fn migration_backup_checksum_and_newer_schema_are_enforced() {
    let app = engine();
    let path = app.root.join("v1.sqlite");
    let db = Connection::open(&path).unwrap();
    db.execute_batch("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL,applied_at TEXT NOT NULL)").unwrap();
    db.execute_batch(include_str!("../migrations/0001_local.sql"))
        .unwrap();
    let legacy_id = id();
    let legacy_op = Operation {
        op_id: id(),
        note_id: legacy_id.clone(),
        base_rev: "1".into(),
        content: "migration pending trash".into(),
        deleted: true,
    };
    db.execute(
        "INSERT INTO notes VALUES(?1,?2,1,2,1,1,?3,?3,NULL)",
        params![legacy_id, legacy_op.content, now()],
    )
    .unwrap();
    db.execute(
        "INSERT INTO outbox(op_id,note_id,snapshot,frozen_generation) VALUES(?1,?2,?3,2)",
        params![
            legacy_op.op_id,
            legacy_id,
            serde_json::to_string(&legacy_op).unwrap()
        ],
    )
    .unwrap();
    db.execute(
        "INSERT INTO schema_migrations VALUES(1,?1,?2)",
        params![
            format!(
                "{:x}",
                sha2::Sha256::digest(include_str!("../migrations/0001_local.sql").as_bytes())
            ),
            now()
        ],
    )
    .unwrap();
    db.pragma_update(None, "user_version", 1).unwrap();
    drop(db);
    let db = db::open(&path).unwrap();
    let preserved = db::note(&db, &legacy_id).unwrap();
    assert_eq!(preserved.content, legacy_op.content);
    assert_eq!(preserved.purge_state, "none");
    assert!(preserved.deleted);
    assert_eq!((preserved.generation, preserved.acknowledged), (2, 1));
    let snapshot: String = db
        .query_row(
            "SELECT snapshot FROM outbox WHERE op_id=?",
            [&legacy_op.op_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(snapshot, serde_json::to_string(&legacy_op).unwrap());
    assert!(path.with_extension("before-v2.sqlite").exists());
    assert_eq!(
        db.pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))
            .unwrap(),
        3
    );
    db.pragma_update(None, "user_version", 99).unwrap();
    drop(db);
    assert_eq!(db::open(&path).unwrap_err().code, "LOCAL_SCHEMA_TOO_NEW");
    let bad = app.root.join("bad.sqlite");
    let db = db::open(&bad).unwrap();
    db.execute(
        "UPDATE schema_migrations SET checksum='invalid' WHERE version=1",
        [],
    )
    .unwrap();
    drop(db);
    assert_eq!(db::open(&bad).unwrap_err().code, "LOCAL_STORAGE_FAILED");
}

// PURGE-01/02/03/04/05/06: local visibility, durable requests and immutable deletion.
#[test]
fn permanent_delete_local_and_pending_conformance() {
    let app = engine();
    let n = invoke(
        &app,
        json!({"action":"create","content":"purge fixture","request_id":id()}),
    );
    let n = invoke(
        &app,
        json!({"action":"set_deleted","id":n["id"],"expected_generation":n["generation"],"deleted":true}),
    );
    let removed = invoke(
        &app,
        json!({"action":"purge","id":n["id"],"expected_generation":n["generation"]}),
    );
    assert_eq!(removed["purge_state"], "purged");
    assert_eq!(removed["content"], "");
    assert!(
        invoke(&app, json!({"action":"list","deleted":true}))
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(app.dispatch(&json!({"action":"set_deleted","id":n["id"],"expected_generation":removed["generation"],"deleted":false})).unwrap_err().code,"NOTE_PURGED");
    let exported = invoke(&app, json!({"action":"export","include_deleted":true}));
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(exported["bytes_base64"].as_str().unwrap())
        .unwrap();
    let zip = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
    assert_eq!(zip.len(), 1);
    let p = account();
    let path = app.path(&p);
    let mut db = db::open(&path).unwrap();
    let nid = id();
    let mut deleted = wire(&nid, "cloud trash", 2, 2);
    deleted.deleted = true;
    db::apply_page(&mut db, &[deleted.clone()], "2", "2", false).unwrap();
    let old = db::note(&db, &nid).unwrap();
    let pending = purge::request(&mut db, &nid, old.generation, true).unwrap();
    assert_eq!(pending.purge_state, "pending");
    let frozen = purge::next(&db).unwrap().unwrap();
    drop(db);
    let mut db = db::open(&path).unwrap();
    assert_eq!(purge::next(&db).unwrap().unwrap(), frozen);
    let mut restored = wire(&nid, "restored remotely", 3, 3);
    restored.deleted = false;
    db::apply_page(&mut db, &[restored], "3", "3", false).unwrap();
    assert!(purge::next(&db).unwrap().is_none());
    assert_eq!(db::note(&db, &nid).unwrap().purge_state, "rejected");
    // A device with a frozen edit must consume deletion instead of creating a copy.
    edit(&db, &nid, "offline edit");
    let (op, g) = db::freeze(&mut db).unwrap().unwrap();
    let mut permanent = wire(&nid, "", 4, 4);
    permanent.deleted = true;
    permanent.purged = true;
    db::apply_page(&mut db, &[permanent.clone()], "4", "4", false).unwrap();
    assert!(db::freeze(&mut db).unwrap().is_none());
    assert!(db::list(&db, "", true).unwrap().is_empty());
    db::acknowledge(
        &mut db,
        &op,
        g,
        &confirmation(&op, json!({"status":"accepted","rev":"4","seq":"4"})),
        &p,
    )
    .unwrap();
    db::apply_page(&mut db, &[deleted, permanent], "4", "4", false).unwrap();
    assert_eq!(db::note(&db, &nid).unwrap().purge_state, "purged");
}

#[test]
fn permanent_delete_waits_for_unknown_write_and_original_delete_ack() {
    let app = engine();
    let p = account();
    let mut db = db::open(&app.path(&p)).unwrap();
    let nid = id();
    seed(&mut db, &nid);
    let mut n = db::note(&db, &nid).unwrap();
    n.deleted = true;
    n.generation += 1;
    db::put(&db, &n).unwrap();
    purge::request(&mut db, &nid, n.generation, true).unwrap();
    assert!(purge::next(&db).unwrap().is_none());
    let (op, g) = db::freeze(&mut db).unwrap().unwrap();
    db::acknowledge(
        &mut db,
        &op,
        g,
        &confirmation(&op, json!({"status":"accepted","rev":"2","seq":"2"})),
        &p,
    )
    .unwrap();
    assert_eq!(purge::next(&db).unwrap().unwrap().2, 2);
}

#[test]
fn permanent_delete_request_does_not_follow_a_conflicting_soft_delete_copy() {
    let app = engine();
    let p = account();
    let mut db = db::open(&app.path(&p)).unwrap();
    let nid = id();
    seed(&mut db, &nid);
    let mut n = db::note(&db, &nid).unwrap();
    n.deleted = true;
    n.generation += 1;
    db::put(&db, &n).unwrap();
    purge::request(&mut db, &nid, n.generation, true).unwrap();
    let (op, generation) = db::freeze(&mut db).unwrap().unwrap();
    db::apply_page(&mut db, &[wire(&nid, "remote edit", 2, 2)], "2", "2", false).unwrap();
    let copy = id();
    let result = confirmation(
        &op,
        json!({"status":"conflict","rev":"2","conflict_note_id":copy,"conflict_rev":"1","conflict_seq":"3"}),
    );
    db::acknowledge(&mut db, &op, generation, &result, &p).unwrap();
    assert_eq!(db::note(&db, &nid).unwrap().purge_state, "rejected");
    assert_eq!(db::note(&db, &copy).unwrap().purge_state, "none");
    assert!(purge::next(&db).unwrap().is_none());
}

#[test]
fn content_size_and_root_url_tls_policy_are_checked() {
    let app = engine();
    assert!(content("中".repeat(MAX_CONTENT / 3 + 1)).is_err());
    assert!(app.normalized_server("http://localhost:8080").is_ok());
    assert!(app.normalized_server("https://example.com/sub").is_err());
    let strict = Engine::open(
        app.root.join("strict"),
        Arc::new(MemoryCredentials::default()),
        false,
    )
    .unwrap();
    assert!(strict.normalized_server("http://localhost:8080").is_err());
    assert_eq!(
        strict.normalized_server("HTTPS://EXAMPLE.COM/").unwrap(),
        "https://example.com"
    );
}

#[test]
fn database_failure_rolls_back_visible_content_and_fts() {
    let app = engine();
    let p = account();
    let mut db = db::open(&app.path(&p)).unwrap();
    let n = id();
    seed(&mut db, &n);
    db.execute_batch("CREATE TRIGGER reject_update BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT,'injected'); END;").unwrap();
    assert!(db::apply_page(&mut db, &[wire(&n, "不应提交", 2, 2)], "2", "2", false).is_err());
    assert_eq!(db::note(&db, &n).unwrap().content, "初始");
    assert_eq!(db::get_state(&db, "cursor").unwrap().unwrap(), "1");
    assert_eq!(db::shadow(&db, &n).unwrap().unwrap().rev, "1");
}
