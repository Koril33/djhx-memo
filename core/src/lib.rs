//! Shared durable note storage and synchronization for all djhx-memo clients.
//! The behavioral contract is in spec/core-api.md and spec/sync-protocol.md.
#![warn(missing_docs)]

mod db;
mod highlight;
mod list_actions;
mod model;
mod purge;
mod sync;
mod update_barrier;
mod update_catalog;
pub use update_catalog::{
    AndroidRelease, Release, UpdateService, validate_published_asset, validate_published_catalog,
};

use base64::Engine as _;
use model::*;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::io::{Cursor, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

uniffi::setup_scaffolding!();

/// Platform credentials remain in the native backend; tokens never enter UI JSON.
#[uniffi::export(callback_interface)]
pub trait CredentialStore: Send + Sync {
    /// Loads an account token, or returns None when credentials are unavailable.
    fn load(&self, profile_id: String) -> Option<String>;
    /// Stores a token securely; false selects process-memory-only credentials.
    fn save(&self, profile_id: String, token: String) -> bool;
    /// Removes the account token from the platform credential service.
    fn remove(&self, profile_id: String);
}

/// Construction errors do not expose database paths or confidential input.
#[derive(Debug, uniffi::Error)]
pub enum CoreError {
    /// The application data directory or schema could not be opened safely.
    Storage,
}
impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("无法安全打开应用本地存储，请检查目录权限或应用版本")
    }
}
impl std::error::Error for CoreError {}

/// Native application handle, shared by foreground and background work.
#[derive(uniffi::Object)]
pub struct MemoApp {
    inner: Arc<Engine>,
}

#[uniffi::export]
impl MemoApp {
    /// Opens isolated profiles and starts a single background sync coordinator.
    #[uniffi::constructor]
    pub fn new(
        root: String,
        credentials: Box<dyn CredentialStore>,
        allow_insecure_http: bool,
    ) -> std::result::Result<Arc<Self>, CoreError> {
        let engine = Engine::open(
            PathBuf::from(root),
            Arc::from(credentials),
            allow_insecure_http,
        )
        .map_err(|_| CoreError::Storage)?;
        let engine = Arc::new(engine);
        Engine::start(&engine);
        Ok(Arc::new(Self { inner: engine }))
    }

    /// Executes the documented JSON bridge and returns a safe result envelope.
    pub fn invoke(&self, request_json: String) -> String {
        let result = serde_json::from_str::<Value>(&request_json)
            .map_err(AppError::from)
            .and_then(|request| self.inner.dispatch(&request));
        match result {
            Ok(data) => json!({"ok":true,"data":data}).to_string(),
            Err(error) => json!({"ok":false,"error":error}).to_string(),
        }
    }
}

pub(crate) struct Engine {
    activity: Arc<update_barrier::ActivityGate>,
    root: PathBuf,
    _root_lock: std::fs::File,
    registry: Mutex<Registry>,
    credentials: Arc<dyn CredentialStore>,
    tokens: Mutex<HashMap<String, String>>,
    credentials_loaded: Mutex<HashSet<String>>,
    persistent: Mutex<HashMap<String, bool>>,
    statuses: Mutex<HashMap<String, SyncInfo>>,
    retry_after: Mutex<HashMap<String, Instant>>,
    auto_blocked: Mutex<HashSet<String>>,
    requested: Mutex<HashSet<String>>,
    manual_requested: Mutex<HashSet<String>>,
    running: Mutex<HashSet<String>>,
    finished: Condvar,
    redirects_seen: Mutex<HashMap<String, Value>>,
    http: reqwest::blocking::Client,
    allow_http: bool,
    foreground: AtomicBool,
    event: AtomicU64,
}

fn lock<T>(m: &Mutex<T>) -> Result<MutexGuard<'_, T>> {
    m.lock().map_err(|_| {
        AppError::new(
            "LOCAL_STORAGE_FAILED",
            "本地状态暂时不可用，请重启应用并保留编辑内容",
        )
    })
}

impl Engine {
    fn open(
        root: PathBuf,
        credentials: Arc<dyn CredentialStore>,
        allow_http: bool,
    ) -> Result<Self> {
        std::fs::create_dir_all(&root)?;
        let root_lock = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(root.join(".instance.lock"))?;
        root_lock
            .try_lock()
            .map_err(|_| AppError::new("LOCAL_STORAGE_FAILED", "已有实例正在使用此数据目录"))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700))?;
        }
        let registry = if root.join("profiles.json").exists() {
            serde_json::from_slice(&std::fs::read(root.join("profiles.json"))?)?
        } else {
            Registry::default()
        };
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|_| AppError::network())?
        .with_root_certificates(rustls::RootCertStore::from_iter(
            webpki_roots::TLS_SERVER_ROOTS.iter().cloned(),
        ))
        .with_no_client_auth();
        let http = reqwest::blocking::Client::builder()
            .use_preconfigured_tls(tls)
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(35))
            .redirect(reqwest::redirect::Policy::none())
            .user_agent(concat!("djhx-memo/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| AppError::network())?;
        let this = Self {
            activity: Arc::default(),
            root,
            _root_lock: root_lock,
            registry: Mutex::new(registry),
            credentials,
            tokens: Mutex::new(HashMap::new()),
            credentials_loaded: Mutex::new(HashSet::new()),
            persistent: Mutex::new(HashMap::new()),
            statuses: Mutex::new(HashMap::new()),
            retry_after: Mutex::new(HashMap::new()),
            auto_blocked: Mutex::new(HashSet::new()),
            requested: Mutex::new(HashSet::new()),
            manual_requested: Mutex::new(HashSet::new()),
            running: Mutex::new(HashSet::new()),
            finished: Condvar::new(),
            redirects_seen: Mutex::new(HashMap::new()),
            http,
            allow_http,
            foreground: AtomicBool::new(true),
            event: AtomicU64::new(1),
        };
        let registry = lock(&this.registry)?;
        this.persist_registry(&registry)?;
        let profile = registry
            .profiles
            .iter()
            .find(|p| p.id == registry.selected)
            .ok_or_else(|| AppError::new("LOCAL_STORAGE_FAILED", "空间登记损坏，已保留数据"))?;
        db::open(&this.path(profile))?;
        drop(registry);
        Ok(this)
    }

    fn persist_registry(&self, registry: &Registry) -> Result<()> {
        let temporary = self.root.join(format!("profiles-{}.tmp", id()));
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        db::private_file(&temporary)?;
        file.write_all(&serde_json::to_vec_pretty(registry)?)?;
        file.sync_all()?;
        std::fs::rename(temporary, self.root.join("profiles.json"))?;
        #[cfg(unix)]
        std::fs::File::open(&self.root)?.sync_all()?;
        Ok(())
    }
    fn path(&self, profile: &Profile) -> PathBuf {
        self.root.join(format!("{}.sqlite", profile.id))
    }
    fn profile(&self, request: &Value) -> Result<Profile> {
        let registry = lock(&self.registry)?;
        if request
            .get("profile_id")
            .and_then(Value::as_str)
            .is_some_and(|p| p != registry.selected)
        {
            return Err(AppError::new(
                "PROFILE_CHANGED",
                "当前空间已变化，原编辑内容请复制保存",
            ));
        }
        registry
            .profiles
            .iter()
            .find(|p| p.id == registry.selected)
            .cloned()
            .ok_or_else(|| AppError::new("NOT_FOUND", "空间不存在"))
    }
    fn fixed_profile(&self, id: &str) -> Result<Profile> {
        lock(&self.registry)?
            .profiles
            .iter()
            .find(|p| p.id == id)
            .cloned()
            .ok_or_else(|| AppError::new("NOT_FOUND", "空间不存在"))
    }
    fn changed(&self, profile: &Profile) {
        self.event.fetch_add(1, Ordering::SeqCst);
        if profile.kind == "account"
            && let Ok(mut wanted) = self.requested.lock()
        {
            wanted.insert(profile.id.clone());
        }
    }
    fn token(&self, profile: &Profile) -> Result<String> {
        // Serialize credential loading with login/logout and recovery. In
        // particular, a slow platform load cannot resurrect an old session.
        let registry = lock(&self.registry)?;
        if registry.logged_out.contains(&profile.id) {
            return Err(AppError::new(
                "AUTH_REQUIRED",
                "请重新登录，当前笔记仍可离线使用",
            ));
        }
        if let Some(token) = lock(&self.tokens)?.get(&profile.id).cloned() {
            return Ok(token);
        }
        if !lock(&self.credentials_loaded)?.insert(profile.id.clone()) {
            return Err(AppError::new(
                "AUTH_REQUIRED",
                "请重新登录，当前笔记仍可离线使用",
            ));
        }
        if let Some(token) = self.credentials.load(profile.id.clone()) {
            lock(&self.tokens)?.insert(profile.id.clone(), token.clone());
            lock(&self.persistent)?.insert(profile.id.clone(), true);
            return Ok(token);
        }
        Err(AppError::new(
            "AUTH_REQUIRED",
            "请重新登录，当前笔记仍可离线使用",
        ))
    }
    fn writable(&self, profile: &Profile) -> Result<MutexGuard<'_, Registry>> {
        let registry = lock(&self.registry)?;
        if registry.selected != profile.id {
            return Err(AppError::new(
                "PROFILE_CHANGED",
                "空间已变化，请保留原编辑内容",
            ));
        }
        if registry
            .profiles
            .iter()
            .find(|p| p.id == profile.id)
            .is_none_or(|p| p.read_only)
        {
            return Err(AppError::new(
                "RECOVERY_REQUIRED",
                "恢复空间为只读，请选择复制到账号空间",
            ));
        }
        Ok(registry)
    }

    fn dispatch(&self, request: &Value) -> Result<Value> {
        let _activity = self.activity.enter()?;
        let action = text(request, "action")?;
        if action == "highlight_code" {
            return Ok(json!(highlight::highlight_markdown(&text(
                request, "source"
            )?)));
        }
        if action == "login" || action == "register" {
            return self.authenticate(request, action == "register");
        }
        if action == "switch" {
            let target = text(request, "profile_id")?;
            let mut registry = lock(&self.registry)?;
            let target_profile = registry
                .profiles
                .iter()
                .find(|p| p.id == target)
                .cloned()
                .ok_or_else(|| AppError::new("NOT_FOUND", "空间不存在"))?;
            db::open(&self.path(&target_profile))?;
            registry.selected = target;
            self.persist_registry(&registry)?;
            drop(registry);
            self.changed(&target_profile);
            return Ok(json!(target_profile));
        }
        if action == "foreground" {
            let active = request["active"].as_bool().unwrap_or(true);
            self.foreground.store(active, Ordering::SeqCst);
            let profile = self.profile(&json!({}))?;
            if active {
                self.changed(&profile);
            }
            return Ok(json!({"active":active}));
        }
        let profile = self.profile(request)?;
        if action == "state" {
            return self.state(&profile);
        }
        if action == "sync" {
            lock(&self.auto_blocked)?.remove(&profile.id);
            if request["wait"].as_bool().unwrap_or(false) {
                // Sync failures belong in SyncInfo, including background work.
                let _ = self.synchronize(&profile);
            } else {
                lock(&self.manual_requested)?.insert(profile.id.clone());
                self.changed(&profile);
            }
            return Ok(json!(self.sync_info(&profile)?));
        }
        if action == "logout" {
            return self.logout(&profile);
        }
        if action == "devices" {
            return Ok(self.api(&profile, "GET", "/v1/devices", None)?["devices"].clone());
        }
        if action == "revoke_device" {
            let target = text(request, "id")?;
            if !valid_id(&target) {
                return Err(AppError::new("INVALID_REQUEST", "设备标识无效"));
            }
            let token = self.token(&profile)?;
            self.api_with_token(
                &profile,
                &token,
                "DELETE",
                &format!("/v1/devices/{target}"),
                None,
            )?;
            if profile.device_id.as_deref() == Some(&target) {
                self.forget_token_if_current(&profile, Some(&token))?;
            }
            return Ok(json!({"revoked":true}));
        }
        if action == "import" {
            return self.import(request, &profile);
        }
        if action == "batch_set_deleted" {
            return self.batch_deleted(request, &profile);
        }
        if action == "import_markdown" {
            return self.import_markdown(request, &profile);
        }
        let mut database = db::open(&self.path(&profile))?;
        match action.as_str() {
            "list" => {
                let query = request["query"].as_str().unwrap_or("");
                let deleted = request["deleted"].as_bool().unwrap_or(false);
                Ok(json!(
                    db::list(&database, query, deleted)?
                        .iter()
                        .map(|n| n.view(profile.kind == "account", db::blocked(&database, &n.id)))
                        .collect::<Vec<_>>()
                ))
            }
            "get" => {
                let n = db::note(&database, &text(request, "id")?)?;
                Ok(n.view(profile.kind == "account", db::blocked(&database, &n.id)))
            }
            "create" => {
                let _guard = self.writable(&profile)?;
                let tx =
                    database.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
                let n = db::create(
                    &tx,
                    content(text(request, "content")?)?,
                    &text(request, "request_id")?,
                    profile.kind == "account",
                )?;
                tx.commit()?;
                self.changed(&profile);
                Ok(n.view(profile.kind == "account", false))
            }
            "purge" => {
                let _guard = self.writable(&profile)?;
                let n = purge::request(
                    &mut database,
                    &text(request, "id")?,
                    counter(&text(request, "expected_generation")?)?,
                    profile.kind == "account",
                )?;
                self.changed(&profile);
                Ok(n.view(profile.kind == "account", false))
            }
            "save" | "set_deleted" | "toggle_task" => {
                let _guard = self.writable(&profile)?;
                let tx =
                    database.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
                let requested_id = text(request, "id")?;
                let expected = counter(&text(request, "expected_generation")?)?;
                let mut n = db::note(&tx, &requested_id)?;
                if n.purge_state == "purged" {
                    return Err(AppError::new(
                        "NOTE_PURGED",
                        "笔记已被彻底删除，可将未保存文字另存新笔记",
                    ));
                }
                if n.purge_state == "pending" {
                    return Err(AppError::new("PURGE_PENDING", "笔记正在等待彻底删除"));
                }
                if n.purge_state == "rejected" {
                    n.purge_state = "none".into();
                }
                let mut redirected = false;
                if n.generation != expected {
                    let alias:Option<(String,i64)>=tx.query_row("SELECT target_id,target_generation FROM redirects WHERE source_id=?1 AND source_generation=?2",params![requested_id,expected],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
                    if let Some((target, generation)) = alias {
                        let candidate = db::note(&tx, &target)?;
                        // The alias only redirects identity and generation; it
                        // must not bypass the destination's deletion barrier.
                        if candidate.purge_state == "purged" {
                            return Err(AppError::new(
                                "NOTE_PURGED",
                                "笔记已被彻底删除，可将未保存文字另存新笔记",
                            ));
                        }
                        if candidate.purge_state == "pending" {
                            return Err(AppError::new("PURGE_PENDING", "笔记正在等待彻底删除"));
                        }
                        if candidate.generation == generation {
                            n = candidate;
                            redirected = true;
                        } else {
                            return Err(AppError::new(
                                "LOCAL_GENERATION_CONFLICT",
                                "冲突副本已有新代次，请保留当前缓冲并重新读取",
                            ));
                        }
                    } else {
                        return Err(AppError::new(
                            "LOCAL_GENERATION_CONFLICT",
                            "笔记已有新代次，请保留当前缓冲并重新读取",
                        ));
                    }
                }
                if n.generation == n.acknowledged
                    && let Some(shadow) = db::shadow(&tx, &n.id)?
                {
                    n.base_rev = counter(&shadow.rev)?;
                }
                match action.as_str() {
                    "save" => n.content = content(text(request, "content")?)?,
                    "set_deleted" => {
                        n.deleted = request["deleted"]
                            .as_bool()
                            .ok_or_else(|| AppError::new("INVALID_REQUEST", "缺少删除状态"))?
                    }
                    _ => {
                        n.content = toggle_task(
                            &n.content,
                            request["line"]
                                .as_u64()
                                .ok_or_else(|| AppError::new("INVALID_REQUEST", "任务行无效"))?
                                as usize,
                        )?
                    }
                }
                n.generation += 1;
                n.updated_at = now();
                if profile.kind != "account" {
                    n.acknowledged = n.generation;
                }
                db::put(&tx, &n)?;
                tx.commit()?;
                self.changed(&profile);
                let mut view = n.view(profile.kind == "account", db::blocked(&database, &n.id));
                if redirected {
                    view["redirected_from"] = json!(requested_id);
                }
                Ok(view)
            }
            "export" => export(
                &database,
                &profile,
                request["include_deleted"].as_bool().unwrap_or(false),
                request["deleted_only"].as_bool().unwrap_or(false),
            ),
            _ => Err(AppError::new("INVALID_REQUEST", "不支持的核心操作")),
        }
    }

    fn sync_info(&self, profile: &Profile) -> Result<SyncInfo> {
        // Recovery can change this profile's kind while a sync call waits.
        let profile = self.fixed_profile(&profile.id)?;
        let db = db::open(&self.path(&profile))?;
        let mut status = lock(&self.statuses)?
            .get(&profile.id)
            .cloned()
            .unwrap_or_else(|| {
                SyncInfo::new(if profile.kind == "local" {
                    "local"
                } else if profile.kind == "recovery" {
                    "recovery_required"
                } else {
                    "pending"
                })
            });
        status.pending = if profile.kind == "account" {
            db::pending(&db)?
        } else {
            0
        };
        if status.last_success.is_none() {
            status.last_success = db::get_state(&db, "last_success")?;
        }
        if profile.kind == "account" && self.token(&profile).is_err() {
            status.state = "auth_required".into();
        } else if status.state == "synced" && status.pending > 0 {
            status.state = "pending".into();
        }
        Ok(status)
    }
    fn state(&self, profile: &Profile) -> Result<Value> {
        let registry = lock(&self.registry)?.clone();
        let db = db::open(&self.path(profile))?;
        let local = db::open(&self.root.join("local.sqlite"))?;
        let import_count = if profile.kind == "account" {
            self.import_count(&local, &db, "local")?
        } else {
            0
        };
        let redirects = db::redirects(&db)?;
        lock(&self.redirects_seen)?.insert(profile.id.clone(), redirects.clone());
        Ok(
            json!({"profile":profile,"profiles":registry.profiles,"event_seq":self.event.load(Ordering::SeqCst).to_string(),"sync":self.sync_info(profile)?,"credential_persistent":lock(&self.persistent)?.get(&profile.id).copied().unwrap_or(false),"local_import_count":import_count,"redirects":redirects}),
        )
    }
    fn import_count(
        &self,
        source: &Connection,
        target: &Connection,
        source_id: &str,
    ) -> Result<usize> {
        let mut count = 0;
        for n in db::list(source, "", false)? {
            let exists:bool=target.query_row("SELECT EXISTS(SELECT 1 FROM imports WHERE source_profile_id=?1 AND source_note_id=?2 AND source_generation=?3)",params![source_id,n.id,n.generation],|r|r.get(0))?;
            if !exists {
                count += 1;
            }
        }
        Ok(count)
    }
    fn import(&self, request: &Value, target: &Profile) -> Result<Value> {
        if target.kind != "account" {
            return Err(AppError::new("INVALID_REQUEST", "请选择要导入的账号空间"));
        }
        let source_id = request["source_profile_id"].as_str().unwrap_or("local");
        let source = self.fixed_profile(source_id)?;
        if source.kind != "local"
            && !(source.kind == "recovery"
                && source.user_id == target.user_id
                && source.server_url == target.server_url)
        {
            return Err(AppError::new(
                "INVALID_REQUEST",
                "不允许在账号间自动复制笔记",
            ));
        }
        let _guard = self.writable(target)?;
        let source_db = db::open(&self.path(&source))?;
        let mut target_db = db::open(&self.path(target))?;
        let ids: Option<HashSet<&str>> = request
            .get("ids")
            .and_then(Value::as_array)
            .map(|v| v.iter().filter_map(Value::as_str).collect());
        let mut imported = 0;
        for n in db::list(&source_db, "", false)? {
            if ids.as_ref().is_some_and(|ids| !ids.contains(n.id.as_str())) {
                continue;
            }
            let tx =
                target_db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
            let exists:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM imports WHERE source_profile_id=?1 AND source_note_id=?2 AND source_generation=?3)",params![source.id,n.id,n.generation],|r|r.get(0))?;
            if !exists {
                let mut copy = n.clone();
                copy.id = id();
                copy.generation = 1;
                copy.acknowledged = 0;
                copy.base_rev = 0;
                copy.created_at = now();
                copy.updated_at = copy.created_at.clone();
                copy.conflict = None;
                db::put(&tx, &copy)?;
                tx.execute(
                    "INSERT INTO imports VALUES(?1,?2,?3,?4)",
                    params![source.id, n.id, n.generation, copy.id],
                )?;
                imported += 1;
            }
            tx.commit()?;
        }
        self.changed(target);
        Ok(json!({"imported":imported}))
    }
}

fn export(
    db: &Connection,
    profile: &Profile,
    include_deleted: bool,
    deleted_only: bool,
) -> Result<Value> {
    let tx = db.unchecked_transaction()?;
    let mut notes = if deleted_only {
        Vec::new()
    } else {
        db::list(&tx, "", false)?
    };
    if include_deleted || deleted_only {
        notes.extend(db::list(&tx, "", true)?);
    }
    notes.sort_by(|a, b| a.id.cmp(&b.id));
    let mut archive = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    let mut manifest = Vec::new();
    for n in notes {
        if matches!(n.purge_state.as_str(), "pending" | "purged") {
            continue;
        }
        if !valid_id(&n.id) {
            return Err(AppError::new("EXPORT_FAILED", "笔记标识无效，导出已停止"));
        }
        let file = format!("{}.md", n.id);
        archive
            .start_file(&file, options)
            .map_err(|_| AppError::new("EXPORT_FAILED", "无法建立导出文件"))?;
        archive
            .write_all(n.content.as_bytes())
            .map_err(|_| AppError::new("EXPORT_FAILED", "导出内容未完整写入"))?;
        manifest.push(json!({"id":n.id,"file":file,"title":title(&n.content),"deleted":n.deleted,"conflict":n.conflict}));
    }
    archive
        .start_file("manifest.json", options)
        .map_err(|_| AppError::new("EXPORT_FAILED", "无法建立导出清单"))?;
    archive.write_all(&serde_json::to_vec_pretty(&json!({"format_version":1,"exported_at":now(),"source_kind":profile.kind,"notes":manifest}))?)?;
    let bytes = archive
        .finish()
        .map_err(|_| AppError::new("EXPORT_FAILED", "导出文件未完成"))?
        .into_inner();
    tx.commit()?;
    Ok(
        json!({"filename":format!("djhx-memo-{}.zip",chrono::Utc::now().format("%Y%m%d-%H%M%S")),"bytes_base64":base64::engine::general_purpose::STANDARD.encode(bytes)}),
    )
}

fn toggle_task(source: &str, line: usize) -> Result<String> {
    use pulldown_cmark::{Event, Options, Parser};
    for (event, range) in Parser::new_ext(source, Options::ENABLE_TASKLISTS).into_offset_iter() {
        if let Event::TaskListMarker(checked) = event {
            let source_line = source[..range.start]
                .bytes()
                .filter(|c| *c == b'\n')
                .count();
            if source_line == line {
                let mut text = source.to_owned();
                let marker = &source[range.clone()];
                if let Some(offset) = marker.find('[') {
                    let start = range.start + offset + 1;
                    text.replace_range(start..start + 1, if checked { " " } else { "x" });
                    return Ok(text);
                }
            }
        }
    }
    Err(AppError::new(
        "INVALID_REQUEST",
        "当前行不是可勾选的 Markdown 任务",
    ))
}

#[cfg(test)]
mod tests;
