use chrono::{SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(crate) const MAX_CONTENT: usize = 1_048_576;
pub(crate) type Result<T> = std::result::Result<T, AppError>;

#[derive(Debug, Clone, Serialize)]
pub(crate) struct AppError {
    pub code: String,
    pub message: String,
    pub retryable: bool,
}

impl AppError {
    pub fn new(code: &str, message: &str) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            retryable: false,
        }
    }
    pub fn network() -> Self {
        Self {
            code: "NETWORK_UNAVAILABLE".into(),
            message: "网络暂时不可用，已保存的本地内容不受影响".into(),
            retryable: true,
        }
    }
}
impl From<rusqlite::Error> for AppError {
    fn from(_: rusqlite::Error) -> Self {
        Self::new(
            "LOCAL_STORAGE_FAILED",
            "本地数据库操作失败，请保留编辑内容后重试",
        )
    }
}
impl From<std::io::Error> for AppError {
    fn from(_: std::io::Error) -> Self {
        Self::new(
            "LOCAL_STORAGE_FAILED",
            "无法读写本地文件，请检查权限和剩余空间",
        )
    }
}
impl From<serde_json::Error> for AppError {
    fn from(_: serde_json::Error) -> Self {
        Self::new("INVALID_REQUEST", "数据格式不符合要求")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Profile {
    pub id: String,
    pub kind: String,
    pub label: String,
    pub server_url: Option<String>,
    pub user_id: Option<String>,
    pub username: Option<String>,
    pub device_id: Option<String>,
    pub read_only: bool,
}
impl Profile {
    pub fn local() -> Self {
        Self {
            id: "local".into(),
            kind: "local".into(),
            label: "本机笔记".into(),
            server_url: None,
            user_id: None,
            username: None,
            device_id: None,
            read_only: false,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Registry {
    pub selected: String,
    pub profiles: Vec<Profile>,
    #[serde(default)]
    pub logged_out: std::collections::HashSet<String>,
}
impl Default for Registry {
    fn default() -> Self {
        Self {
            selected: "local".into(),
            profiles: vec![Profile::local()],
            logged_out: std::collections::HashSet::new(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Conflict {
    pub source_note_id: String,
    pub source_device_id: String,
    pub source_device_name: String,
    pub source_op_id: String,
    pub reason: String,
    pub detected_at: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct WireNote {
    pub id: String,
    pub content: String,
    pub deleted: bool,
    #[serde(default)]
    pub purged: bool,
    pub rev: String,
    pub seq: String,
    pub created_at: String,
    pub updated_at: String,
    pub conflict: Option<Conflict>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Operation {
    pub op_id: String,
    pub note_id: String,
    pub base_rev: String,
    pub content: String,
    pub deleted: bool,
}

#[derive(Clone, Debug)]
pub(crate) struct LocalNote {
    pub id: String,
    pub content: String,
    pub deleted: bool,
    pub purge_state: String,
    pub generation: i64,
    pub acknowledged: i64,
    pub base_rev: i64,
    pub created_at: String,
    pub updated_at: String,
    pub conflict: Option<Conflict>,
}
impl LocalNote {
    pub fn view(&self, account: bool, blocked: bool) -> Value {
        json!({"id":self.id,"content":self.content,"title":title(&self.content),"deleted":self.deleted,"purge_state":self.purge_state,
            "generation":self.generation.to_string(),"created_at":self.created_at,"updated_at":self.updated_at,
            "conflict":self.conflict,"save_state":"saved", "sync_state":if !account {"local"} else if self.purge_state=="pending" {"pending"} else if blocked {"blocked"} else if self.generation>self.acknowledged {"pending"} else {"synced"}})
    }
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct SyncInfo {
    pub state: String,
    pub pending: usize,
    pub last_success: Option<String>,
    pub error: Option<AppError>,
}
impl SyncInfo {
    pub fn new(state: &str) -> Self {
        Self {
            state: state.into(),
            pending: 0,
            last_success: None,
            error: None,
        }
    }
}

pub(crate) fn now() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}
pub(crate) fn id() -> String {
    uuid::Uuid::new_v4().to_string()
}
pub(crate) fn valid_id(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|u| u.get_version_num() == 4 && u.to_string() == value)
}
pub(crate) fn counter(value: &str) -> Result<i64> {
    if value.is_empty()
        || (value.len() > 1 && value.starts_with('0'))
        || !value.bytes().all(|c| c.is_ascii_digit())
    {
        return Err(AppError::new("INVALID_REQUEST", "版本或代次格式无效"));
    }
    value
        .parse()
        .map_err(|_| AppError::new("INVALID_REQUEST", "版本或代次超出范围"))
}
pub(crate) fn text(request: &Value, key: &str) -> Result<String> {
    request
        .get(key)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| AppError::new("INVALID_REQUEST", "缺少必要的请求参数"))
}
pub(crate) fn content(value: String) -> Result<String> {
    let normalized = value.replace("\r\n", "\n").replace('\r', "\n");
    if normalized.len() > MAX_CONTENT {
        return Err(AppError::new(
            "NOTE_TOO_LARGE",
            "笔记超过 1 MiB，请复制保存后拆分",
        ));
    }
    Ok(normalized)
}
pub(crate) fn title(value: &str) -> String {
    let first = value
        .lines()
        .find(|s| !s.trim().is_empty())
        .unwrap_or("")
        .trim();
    let stripped = first.trim_start_matches('#').trim();
    if stripped.is_empty() {
        "无标题".into()
    } else {
        stripped.chars().take(80).collect()
    }
}
