use super::*;
use std::io::{BufRead, BufReader, Read};

struct SyncLease<'a> {
    engine: &'a Engine,
    profile_id: String,
}
impl Drop for SyncLease<'_> {
    fn drop(&mut self) {
        if let Ok(mut running) = self.engine.running.lock() {
            running.remove(&self.profile_id);
            self.engine.finished.notify_all();
        }
    }
}

impl Engine {
    pub(crate) fn api(
        &self,
        profile: &Profile,
        method: &str,
        path: &str,
        body: Option<&Value>,
    ) -> Result<Value> {
        let token = self.token(profile)?;
        self.api_with_token(profile, &token, method, path, body)
    }
    pub(crate) fn api_with_token(
        &self,
        profile: &Profile,
        token: &str,
        method: &str,
        path: &str,
        body: Option<&Value>,
    ) -> Result<Value> {
        let server = profile
            .server_url
            .as_ref()
            .ok_or_else(|| AppError::new("INVALID_REQUEST", "当前空间没有服务器"))?;
        let response = self
            .http
            .request(
                method
                    .parse()
                    .map_err(|_| AppError::new("INVALID_REQUEST", "请求方法无效"))?,
                format!("{server}{path}"),
            )
            .bearer_auth(token)
            .header("X-Memo-Protocol", "2")
            .header("Accept", "application/json");
        let response = if let Some(body) = body {
            response.json(body)
        } else {
            response
        };
        let response = response.send().map_err(|_| AppError::network())?;
        if response.status().as_u16() == 401
            && !self.forget_token_if_current(profile, Some(token))?
        {
            return Err(session_changed());
        }
        if matches!(response.status().as_u16(), 429 | 503)
            && let Some(wait) = response
                .headers()
                .get("Retry-After")
                .and_then(|v| v.to_str().ok())
                .and_then(retry_delay)
            && let Some(deadline) = Instant::now().checked_add(wait)
        {
            lock(&self.retry_after)?.insert(profile.id.clone(), deadline);
        }
        self.decode(response)
    }
    fn decode(&self, response: reqwest::blocking::Response) -> Result<Value> {
        let status = response.status();
        if status.as_u16() == 204 {
            return Ok(Value::Null);
        }
        if response
            .content_length()
            .is_some_and(|n| n > 8 * 1024 * 1024)
        {
            return Err(AppError::new("INVALID_REQUEST", "服务器响应超过协议限制"));
        }
        let mut bytes = Vec::new();
        std::io::Read::take(response, 8 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| AppError::network())?;
        if bytes.len() > 8 * 1024 * 1024 {
            return Err(AppError::new("INVALID_REQUEST", "服务器响应超过协议限制"));
        }
        let json: Value = serde_json::from_slice(&bytes)?;
        if !status.is_success() {
            let code = json["error"]["code"]
                .as_str()
                .unwrap_or(if status.as_u16() == 401 {
                    "AUTH_REQUIRED"
                } else {
                    "SERVER_BUSY"
                });
            return Err(AppError {
                code: code.into(),
                message: public_message(code).into(),
                retryable: json["error"]["retryable"]
                    .as_bool()
                    .unwrap_or(status.is_server_error() || status.as_u16() == 429),
            });
        }
        Ok(json)
    }
    pub(crate) fn normalized_server(&self, input: &str) -> Result<String> {
        let mut url = reqwest::Url::parse(input.trim())
            .map_err(|_| AppError::new("INVALID_REQUEST", "服务器地址格式无效"))?;
        if !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || !url.path().trim_matches('/').is_empty()
            || url.host_str().is_none()
        {
            return Err(AppError::new(
                "INVALID_REQUEST",
                "服务器地址须为不含账号、子路径或查询参数的根地址",
            ));
        }
        if url.scheme() != "https" && !(self.allow_http && url.scheme() == "http") {
            return Err(AppError::new(
                "INVALID_REQUEST",
                "正式客户端仅允许 HTTPS 服务器",
            ));
        }
        url.set_path("");
        Ok(url.to_string().trim_end_matches('/').to_owned())
    }
    pub(crate) fn authenticate(&self, request: &Value, register: bool) -> Result<Value> {
        let server = self.normalized_server(&text(request, "server_url")?)?;
        let username = text(request, "username")?;
        let password = text(request, "password")?;
        let device_name = text(request, "device_name")?;
        let known = lock(&self.registry)?
            .profiles
            .iter()
            .find(|p| {
                p.kind == "account"
                    && p.server_url.as_deref() == Some(&server)
                    && p.username.as_deref() == Some(&username)
            })
            .cloned();
        let device_id = known
            .as_ref()
            .and_then(|p| p.device_id.clone())
            .unwrap_or_else(id);
        let mut body = json!({"username":username,"password":password,"device_id":device_id,"device_name":device_name});
        if register {
            body["invite_code"] = json!(text(request, "invite_code")?);
        }
        let response = self
            .http
            .post(format!(
                "{server}/v1/auth/{}",
                if register { "register" } else { "login" }
            ))
            .json(&body)
            .send()
            .map_err(|_| AppError::network())?;
        drop(body);
        let result = self.decode(response)?;
        let user = text(&result, "user_id")?;
        let epoch = text(&result, "epoch")?;
        let token = text(&result, "token")?;
        if !valid_id(&user)
            || !valid_id(&epoch)
            || result["protocol_version"] != 2
            || text(&result, "device_id")? != device_id
        {
            return Err(AppError::new(
                "PROTOCOL_UNSUPPORTED",
                "服务器协议版本或账号信息不兼容",
            ));
        }
        let mut registry = lock(&self.registry)?;
        let profile = registry
            .profiles
            .iter()
            .find(|p| {
                p.kind == "account"
                    && p.server_url.as_deref() == Some(&server)
                    && p.user_id.as_deref() == Some(&user)
            })
            .cloned()
            .unwrap_or_else(|| Profile {
                id: id(),
                kind: "account".into(),
                label: format!("{username} · {server}"),
                server_url: Some(server),
                user_id: Some(user),
                username: Some(username),
                device_id: Some(device_id),
                read_only: false,
            });
        let db = db::open(&self.path(&profile))?;
        if db::get_state(&db, "epoch")?.is_none() {
            db::set_state(&db, "epoch", &epoch)?;
        }
        if !registry.profiles.iter().any(|p| p.id == profile.id) {
            registry.profiles.push(profile.clone());
        }
        registry.selected = profile.id.clone();
        registry.logged_out.remove(&profile.id);
        self.persist_registry(&registry)?;
        let persisted = self.credentials.save(profile.id.clone(), token.clone());
        lock(&self.tokens)?.insert(profile.id.clone(), token);
        lock(&self.credentials_loaded)?.insert(profile.id.clone());
        lock(&self.persistent)?.insert(profile.id.clone(), persisted);
        lock(&self.statuses)?.remove(&profile.id);
        lock(&self.auto_blocked)?.remove(&profile.id);
        lock(&self.retry_after)?.remove(&profile.id);
        drop(registry);
        self.changed(&profile);
        let local = db::open(&self.root.join("local.sqlite"))?;
        Ok(
            json!({"profile":profile,"import_count":self.import_count(&local,&db,"local")?,"credential_persistent":persisted}),
        )
    }
    pub(crate) fn forget_token_if_current(
        &self,
        profile: &Profile,
        expected: Option<&str>,
    ) -> Result<bool> {
        let mut registry = lock(&self.registry)?;
        if !lock(&self.tokens)?.contains_key(&profile.id)
            && registry.logged_out.contains(&profile.id)
        {
            return Ok(true);
        }
        if lock(&self.tokens)?.get(&profile.id).map(String::as_str) != expected {
            return Ok(false);
        }
        registry.logged_out.insert(profile.id.clone());
        self.persist_registry(&registry)?;
        lock(&self.tokens)?.remove(&profile.id);
        lock(&self.persistent)?.remove(&profile.id);
        self.credentials.remove(profile.id.clone());
        let mut info = SyncInfo::new("auth_required");
        info.error = Some(AppError::new("AUTH_REQUIRED", "请重新登录，本地笔记已保留"));
        lock(&self.statuses)?.insert(profile.id.clone(), info);
        self.event.fetch_add(1, Ordering::SeqCst);
        Ok(true)
    }
    pub(crate) fn logout(&self, profile: &Profile) -> Result<Value> {
        let token = self.token(profile).ok();
        let remote_revoked = token.as_deref().is_some_and(|token| {
            self.api_with_token(profile, token, "POST", "/v1/auth/logout", Some(&json!({})))
                .is_ok()
        });
        if !self.forget_token_if_current(profile, token.as_deref())? {
            return Err(session_changed());
        }
        let mut registry = lock(&self.registry)?;
        // A delayed logout cannot move a different account's selected space.
        if registry.selected == profile.id && registry.logged_out.contains(&profile.id) {
            registry.selected = "local".into();
        }
        self.persist_registry(&registry)?;
        self.event.fetch_add(1, Ordering::SeqCst);
        Ok(json!({"remote_revoked":remote_revoked}))
    }
    fn recover(&self, profile: &Profile, epoch: &str, token: String) -> Result<()> {
        if !valid_id(epoch) {
            return Err(AppError::new("INVALID_REQUEST", "恢复代次无效"));
        }
        // The old file is never removed or reset. Holding the registry lock
        // serializes this transition with all local write transactions.
        let mut registry = lock(&self.registry)?;
        if lock(&self.tokens)?.get(&profile.id) != Some(&token) {
            return Err(session_changed());
        }
        let mut updated = registry.clone();
        let current = updated
            .profiles
            .iter_mut()
            .find(|p| p.id == profile.id)
            .ok_or_else(|| AppError::new("NOT_FOUND", "空间不存在"))?;
        if current.kind != "account" {
            return Err(AppError::new("RECOVERY_REQUIRED", "已保全恢复空间"));
        }
        current.kind = "recovery".into();
        current.read_only = true;
        current.label = format!(
            "恢复空间 · {} · {}",
            profile.username.as_deref().unwrap_or("账号"),
            now()
        );
        let mut fresh = profile.clone();
        fresh.id = id();
        fresh.kind = "account".into();
        fresh.read_only = false;
        let fresh_db = db::open(&self.path(&fresh))?;
        db::set_state(&fresh_db, "epoch", epoch)?;
        updated.profiles.push(fresh.clone());
        if updated.selected == profile.id {
            updated.selected = fresh.id.clone();
        }
        updated.logged_out.insert(profile.id.clone());
        self.persist_registry(&updated)?;
        *registry = updated;
        let stored = self.credentials.save(fresh.id.clone(), token.clone());
        lock(&self.tokens)?.insert(fresh.id.clone(), token);
        lock(&self.persistent)?.insert(fresh.id.clone(), stored);
        lock(&self.tokens)?.remove(&profile.id);
        lock(&self.persistent)?.remove(&profile.id);
        self.credentials.remove(profile.id.clone());
        lock(&self.statuses)?.insert(profile.id.clone(), SyncInfo::new("recovery_required"));
        drop(registry);
        self.changed(&fresh);
        Ok(())
    }

    fn pull(&self, profile: &Profile, epoch: &str) -> Result<()> {
        let mut db = db::open(&self.path(profile))?;
        let mut cursor = db::get_state(&db, "cursor")?.unwrap_or_else(|| "0".into());
        let mut upper: Option<String> = None;
        loop {
            let mut url = format!("/v1/sync/pull?epoch={epoch}&cursor={cursor}&limit=50");
            if let Some(until) = &upper {
                url.push_str(&format!("&until={until}"));
            }
            let page = self.api(profile, "GET", &url, None)?;
            if text(&page, "epoch")? != epoch {
                return Err(AppError::new("SYNC_EPOCH_MISMATCH", "服务器恢复代次已变化"));
            }
            let next = text(&page, "next_cursor")?;
            let until = text(&page, "until")?;
            let next_value = counter(&next)?;
            let until_value = counter(&until)?;
            let previous = counter(&cursor)?;
            if next_value < previous
                || until_value < next_value
                || upper.as_ref().is_some_and(|u| u != &until)
            {
                return Err(AppError::new(
                    "CURSOR_INVALID",
                    "服务器返回不连续的同步位置",
                ));
            }
            let changes: Vec<WireNote> = serde_json::from_value(page["changes"].clone())?;
            let mut seen = previous;
            for n in &changes {
                let seq = counter(&n.seq)?;
                if seq <= seen || seq > until_value {
                    return Err(AppError::new("CURSOR_INVALID", "服务器变化顺序无效"));
                }
                seen = seq;
            }
            if seen != next_value {
                return Err(AppError::new("CURSOR_INVALID", "分页末尾与同步位置不一致"));
            }
            let more = page["has_more"]
                .as_bool()
                .ok_or_else(|| AppError::new("INVALID_REQUEST", "同步分页状态缺失"))?;
            if (more && (next_value == previous || next_value >= until_value))
                || (!more && next_value != until_value)
            {
                return Err(AppError::new("CURSOR_INVALID", "同步分页无法前进"));
            }
            db::apply_page(&mut db, &changes, &next, &until, false)?;
            if !changes.is_empty() {
                self.event.fetch_add(1, Ordering::SeqCst);
            }
            cursor = next;
            upper = Some(until);
            if !more {
                break;
            }
        }
        Ok(())
    }

    pub(crate) fn synchronize(&self, profile: &Profile) -> Result<()> {
        let _activity = self.activity.enter()?;
        if profile.kind != "account" {
            return Ok(());
        }
        {
            let mut running = lock(&self.running)?;
            while running.contains(&profile.id) {
                running = self
                    .finished
                    .wait(running)
                    .map_err(|_| AppError::new("LOCAL_STORAGE_FAILED", "同步状态不可用"))?;
            }
            running.insert(profile.id.clone());
        }
        let _lease = SyncLease {
            engine: self,
            profile_id: profile.id.clone(),
        };
        let mut result = (|| {
            let result = self.sync_round(profile);
            if result
                .as_ref()
                .is_err_and(|e| e.code == "CURSOR_INVALID" || e.code == "SYNC_EPOCH_MISMATCH")
                && let (Ok(status), Ok(token)) = (
                    self.api(profile, "GET", "/v1/sync/status", None),
                    self.token(profile),
                )
                && let Some(epoch) = status["epoch"].as_str()
            {
                self.recover(profile, epoch, token)?;
                return Err(AppError::new(
                    "RECOVERY_REQUIRED",
                    "同步历史已变化，旧数据库已保全",
                ));
            }
            result
        })();
        let mut status = self.sync_info(profile)?;
        // Publish the result under the same lock as session replacement: an
        // older 401 must not re-block an account after a successful login.
        let registry = lock(&self.registry)?;
        if result.as_ref().is_err_and(|e| e.code == "AUTH_REQUIRED")
            && !registry.logged_out.contains(&profile.id)
            && lock(&self.tokens)?.contains_key(&profile.id)
        {
            result = Err(session_changed());
        }
        if let Err(error) = &result {
            if !error.retryable {
                lock(&self.auto_blocked)?.insert(profile.id.clone());
            }
            status.state = if error.code == "AUTH_REQUIRED" {
                "auth_required"
            } else if error.code == "RECOVERY_REQUIRED" {
                "recovery_required"
            } else if error.retryable {
                "offline"
            } else {
                "error"
            }
            .into();
            status.error = Some(error.clone());
            lock(&self.statuses)?.insert(profile.id.clone(), status);
            self.event.fetch_add(1, Ordering::SeqCst);
        } else {
            lock(&self.auto_blocked)?.remove(&profile.id);
        }
        drop(registry);
        result
    }
    fn sync_round(&self, profile: &Profile) -> Result<()> {
        if lock(&self.retry_after)?
            .get(&profile.id)
            .is_some_and(|deadline| *deadline > Instant::now())
        {
            return Err(AppError {
                code: "RATE_LIMITED".into(),
                message: public_message("RATE_LIMITED").into(),
                retryable: true,
            });
        }
        let token = self.token(profile)?;
        let status = self.api(profile, "GET", "/v1/sync/status", None)?;
        if status["protocol_version"] != 2 {
            return Err(AppError::new(
                "PROTOCOL_UNSUPPORTED",
                "请升级客户端后再同步",
            ));
        }
        let epoch = text(&status, "epoch")?;
        let mut db = db::open(&self.path(profile))?;
        let old_epoch = db::get_state(&db, "epoch")?;
        if old_epoch.as_deref().is_some_and(|old| old != epoch) {
            self.recover(profile, &epoch, token)?;
            return Err(AppError::new(
                "RECOVERY_REQUIRED",
                "服务器已恢复备份，旧笔记已保全为只读恢复空间",
            ));
        }
        if old_epoch.is_none() {
            db::set_state(&db, "epoch", &epoch)?;
        }
        let head = counter(&text(&status, "head_seq")?)?;
        let cursor = counter(&db::get_state(&db, "cursor")?.unwrap_or_else(|| "0".into()))?;
        if cursor > head {
            self.recover(profile, &epoch, token)?;
            return Err(AppError::new(
                "RECOVERY_REQUIRED",
                "同步历史异常，旧空间已保全",
            ));
        }
        lock(&self.statuses)?.insert(profile.id.clone(), SyncInfo::new("syncing"));
        self.event.fetch_add(1, Ordering::SeqCst);
        self.pull(profile, &epoch)?;
        let mut permanent: Option<AppError> = None;
        for _ in 0..10_000 {
            let current = self.fixed_profile(&profile.id)?;
            if current.kind != "account" {
                break;
            }
            let Some((operation, generation)) = db::freeze(&mut db)? else {
                break;
            };
            let response = self.api(
                profile,
                "POST",
                "/v1/sync/push",
                Some(&json!({"epoch":epoch,"ops":[operation]})),
            )?;
            if text(&response, "epoch")? != epoch {
                return Err(AppError::new("SYNC_EPOCH_MISMATCH", "服务器恢复代次已变化"));
            }
            let results = response["results"]
                .as_array()
                .filter(|v| v.len() == 1)
                .ok_or_else(|| AppError::new("INVALID_REQUEST", "服务器确认数量无效"))?;
            if results[0]["op_id"].as_str() != Some(&operation.op_id) {
                return Err(AppError::new(
                    "INVALID_REQUEST",
                    "服务器确认的操作标识不一致",
                ));
            }
            if results[0]["status"] == "conflict" {
                self.pull(profile, &epoch)?;
            }
            if let Err(error) =
                db::acknowledge(&mut db, &operation, generation, &results[0], profile)
            {
                if error.retryable || results[0]["status"] != "error" {
                    return Err(error);
                }
                permanent = Some(error);
            }
            self.event.fetch_add(1, Ordering::SeqCst);
        }
        while let Some((note_id, op_id, base_rev)) = purge::next(&db)? {
            match self.api(profile, "POST", "/v1/notes/purge", Some(&json!({"epoch":epoch,"op_id":op_id,"note_id":note_id,"base_rev":base_rev.to_string()}))) {
                Ok(response) => {
                    if text(&response, "epoch")? != epoch { return Err(AppError::new("SYNC_EPOCH_MISMATCH", "服务器恢复代次已变化")); }
                    let wire: WireNote = serde_json::from_value(response["note"].clone())?;
                    if wire.id != note_id || counter(&wire.rev)? < base_rev { return Err(AppError::new("INVALID_REQUEST", "永久删除确认不匹配")); }
                    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
                    purge::install(&tx, &wire)?;
                    tx.commit()?;
                }
                Err(error) if matches!(error.code.as_str(), "PURGE_CONFLICT" | "NOTE_NOT_FOUND") => {
                    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
                    purge::reject(&tx, &note_id)?;
                    tx.commit()?;
                    permanent = Some(error);
                }
                Err(error) => return Err(error),
            }
            self.event.fetch_add(1, Ordering::SeqCst);
        }
        self.pull(profile, &epoch)?;
        let mut info = SyncInfo::new("synced");
        info.pending = db::pending(&db)?;
        let blocked: Option<String> = db
            .query_row(
                "SELECT blocked FROM outbox WHERE blocked IS NOT NULL LIMIT 1",
                [],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(error) = permanent.or_else(|| {
            blocked.map(|serialized| {
                let value: Value = serde_json::from_str(&serialized).unwrap_or(Value::Null);
                let code = value["code"].as_str().unwrap_or("INVALID_REQUEST");
                AppError::new(code, public_message(code))
            })
        }) {
            info.state = "error".into();
            info.error = Some(error);
        } else if info.pending > 0 {
            info.state = "pending".into();
        } else {
            let stamp = now();
            db::set_state(&db, "last_success", &stamp)?;
            info.last_success = Some(stamp);
        }
        lock(&self.statuses)?.insert(profile.id.clone(), info);
        self.event.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }

    pub(crate) fn start(this: &Arc<Self>) {
        let weak = Arc::downgrade(this);
        std::thread::spawn(move || {
            let mut next = HashMap::<String, Instant>::new();
            let mut attempts = HashMap::<String, u32>::new();
            loop {
                std::thread::sleep(Duration::from_millis(300));
                let Some(engine) = weak.upgrade() else {
                    break;
                };
                let Ok(profile) = engine.profile(&json!({})) else {
                    continue;
                };
                let manual = engine
                    .manual_requested
                    .lock()
                    .ok()
                    .is_some_and(|mut requested| requested.remove(&profile.id));
                // Tray "sync now" is a one-shot request even when its window
                // is hidden. Background polling/SSE still require foreground.
                if !engine.foreground.load(Ordering::SeqCst) && !manual {
                    continue;
                }
                if profile.kind != "account" || engine.token(&profile).is_err() {
                    continue;
                }
                if engine
                    .auto_blocked
                    .lock()
                    .is_ok_and(|blocked| blocked.contains(&profile.id))
                {
                    continue;
                }
                if engine.retry_after.lock().is_ok_and(|deadlines| {
                    deadlines
                        .get(&profile.id)
                        .is_some_and(|deadline| *deadline > Instant::now())
                }) {
                    if manual && let Ok(mut requested) = engine.manual_requested.lock() {
                        requested.insert(profile.id.clone());
                    }
                    continue;
                }
                let requested = engine
                    .requested
                    .lock()
                    .ok()
                    .is_some_and(|mut p| p.remove(&profile.id));
                let due = next
                    .get(&profile.id)
                    .is_none_or(|deadline| *deadline <= Instant::now());
                if !requested && !manual && !due {
                    continue;
                }
                let result = engine.synchronize(&profile);
                let wait = if result.as_ref().is_err_and(|e| e.retryable) {
                    let n = attempts.entry(profile.id.clone()).or_default();
                    *n = (*n + 1).min(6);
                    Duration::from_secs((1u64 << (*n - 1)).min(60))
                        + Duration::from_millis((engine.event.load(Ordering::Relaxed) % 7) * 83)
                } else {
                    attempts.remove(&profile.id);
                    Duration::from_secs(30)
                };
                next.insert(profile.id, Instant::now() + wait);
            }
        });
        let weak = Arc::downgrade(this);
        std::thread::spawn(move || {
            loop {
                std::thread::sleep(Duration::from_secs(1));
                let Some(engine) = weak.upgrade() else {
                    break;
                };
                if !engine.foreground.load(Ordering::SeqCst) {
                    continue;
                }
                let Ok(profile) = engine.profile(&json!({})) else {
                    continue;
                };
                if profile.kind != "account" {
                    continue;
                }
                let Ok(token) = engine.token(&profile) else {
                    continue;
                };
                let Some(server) = profile.server_url.clone() else {
                    continue;
                };
                let stream = engine
                    .http
                    .get(format!("{server}/v1/events"))
                    .bearer_auth(&token)
                    .header("X-Memo-Protocol", "2")
                    .header("Accept", "text/event-stream")
                    .send();
                let Ok(response) = stream else {
                    continue;
                };
                // Installing must also wait for the SSE credential/database path.
                let Ok(_activity) = engine.activity.enter() else {
                    continue;
                };
                if response.status().as_u16() == 401 {
                    let _ = engine.forget_token_if_current(&profile, Some(&token));
                    continue;
                }
                if !response.status().is_success() {
                    continue;
                }
                // Each line is bounded: sync signals contain UUID and counter,
                // never note content. A timeout bounds account-switch latency.
                let mut reader = BufReader::new(response);
                loop {
                    let mut bytes = Vec::new();
                    let read = std::io::Read::take(&mut reader, 4097).read_until(b'\n', &mut bytes);
                    if read.is_err() || bytes.is_empty() || bytes.len() > 4096 {
                        break;
                    }
                    if !engine.foreground.load(Ordering::SeqCst)
                        || engine.profile(&json!({})).is_ok_and(|p| p.id != profile.id)
                    {
                        break;
                    }
                    let line = String::from_utf8_lossy(&bytes);
                    if let Some(data) = line.strip_prefix("data:")
                        && let Ok(signal) = serde_json::from_str::<Value>(data.trim())
                        && let Ok(db) = db::open(&engine.path(&profile))
                    {
                        let cursor = db::get_state(&db, "cursor")
                            .ok()
                            .flatten()
                            .unwrap_or_else(|| "0".into());
                        let epoch = db::get_state(&db, "epoch")
                            .ok()
                            .flatten()
                            .unwrap_or_default();
                        if signal["epoch"].as_str() != Some(&epoch)
                            || signal["head_seq"].as_str() != Some(&cursor)
                        {
                            engine.changed(&profile);
                        }
                    }
                }
            }
        });
    }
}

fn session_changed() -> AppError {
    AppError {
        code: "SESSION_CHANGED".into(),
        message: "登录凭据已更新，将继续同步".into(),
        retryable: true,
    }
}

fn retry_delay(value: &str) -> Option<Duration> {
    if let Ok(seconds) = value.trim().parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }
    let deadline = chrono::DateTime::parse_from_rfc2822(value).ok()?;
    Some(
        (deadline.with_timezone(&chrono::Utc) - chrono::Utc::now())
            .to_std()
            .unwrap_or_default(),
    )
}

fn public_message(code: &str) -> &'static str {
    match code {
        "AUTH_INVALID_CREDENTIALS" => "用户名或密码不正确",
        "AUTH_REQUIRED" => "登录已过期或设备已被吊销，请重新登录",
        "INVITE_INVALID" => "邀请码无效、已使用或已过期",
        "USERNAME_TAKEN" => "用户名已被使用",
        "RATE_LIMITED" => "请求过于频繁，请稍后重试",
        "DEVICE_ID_UNAVAILABLE" => "设备标识不可用，请重启应用后重试",
        "SYNC_EPOCH_MISMATCH" => "服务器恢复代次发生变化，本地内容已保留",
        "CURSOR_INVALID" => "同步历史位置无效，本地内容已保留",
        "PROTOCOL_UNSUPPORTED" => "服务器协议不兼容，请升级客户端",
        "INVALID_REQUEST" => "请求参数无效，请检查后重试",
        _ => "服务器暂时无法完成请求，本地内容已保留",
    }
}
