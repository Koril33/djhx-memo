#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use base64::{Engine, prelude::BASE64_STANDARD};
use djhx_memo_core::{CredentialStore, MemoApp};
use serde::Serialize;
use serde_json::{Value, json};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
use tauri::{
    Emitter, Manager, State,
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

struct SystemCredentials;
impl CredentialStore for SystemCredentials {
    fn load(&self, profile_id: String) -> Option<String> {
        keyring::Entry::new("app.djhx.memo", &profile_id)
            .ok()?
            .get_password()
            .ok()
    }
    fn save(&self, profile_id: String, token: String) -> bool {
        keyring::Entry::new("app.djhx.memo", &profile_id)
            .and_then(|entry| entry.set_password(&token))
            .is_ok()
    }
    fn remove(&self, profile_id: String) {
        if let Ok(entry) = keyring::Entry::new("app.djhx.memo", &profile_id) {
            let _ = entry.delete_credential();
        }
    }
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
enum Lifecycle {
    Hide,
    Quit,
}
struct Backend {
    core: Arc<MemoApp>,
    tray_available: bool,
    next_lifecycle: AtomicU64,
    pending_lifecycle: Mutex<Option<(u64, Lifecycle)>>,
    sync_item: MenuItem<tauri::Wry>,
    frontend_ready: AtomicBool,
}

#[tauri::command]
async fn core_invoke(
    app: tauri::AppHandle,
    state: State<'_, Backend>,
    request_json: String,
) -> Result<String, String> {
    let core = state.core.clone();
    let result = tauri::async_runtime::spawn_blocking(move || core.invoke(request_json))
        .await
        .map_err(|_| "核心任务未完成，请保留编辑内容并重试。".to_string())?;
    // Menu state is derived only from core's active profile, never from user-supplied Markdown.
    if let Ok(value) = serde_json::from_str::<Value>(&result)
        && let Some(kind) = value.pointer("/data/profile/kind").and_then(Value::as_str)
    {
        let item = &app.state::<Backend>().sync_item;
        let account = kind == "account";
        let _ = item.set_enabled(account);
        let _ = item.set_text(if account {
            "立即同步"
        } else {
            "立即同步（仅本机）"
        });
    }
    Ok(result)
}

#[derive(Serialize)]
struct RuntimeInfo {
    tray_available: bool,
    debug_http: bool,
    version: &'static str,
}
#[tauri::command]
fn runtime_info(state: State<'_, Backend>) -> RuntimeInfo {
    RuntimeInfo {
        tray_available: state.tray_available,
        debug_http: cfg!(debug_assertions),
        version: env!("CARGO_PKG_VERSION"),
    }
}

fn show_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn request_lifecycle(app: &tauri::AppHandle, desired: Lifecycle) {
    let state = app.state::<Backend>();
    if !state.frontend_ready.load(Ordering::Acquire) {
        show_window(app);
        return;
    }
    let Ok(mut pending) = state.pending_lifecycle.lock() else {
        return;
    };
    if let Some((id, kind)) = *pending {
        let _ = app.emit_to("main", "memo-lifecycle", json!({ "id": id, "kind": kind }));
        show_window(app);
        return;
    }
    let id = state.next_lifecycle.fetch_add(1, Ordering::Relaxed);
    let lifecycle = if !state.tray_available {
        Lifecycle::Quit
    } else {
        desired
    };
    *pending = Some((id, lifecycle));
    if app
        .emit_to(
            "main",
            "memo-lifecycle",
            json!({ "id": id, "kind": lifecycle }),
        )
        .is_err()
    {
        *pending = None;
    }
}

#[tauri::command]
fn frontend_ready(state: State<'_, Backend>) {
    state.frontend_ready.store(true, Ordering::Release);
}

#[tauri::command]
fn finish_lifecycle(
    app: tauri::AppHandle,
    state: State<'_, Backend>,
    id: u64,
    saved: bool,
) -> Result<(), String> {
    let mut pending = state
        .pending_lifecycle
        .lock()
        .map_err(|_| "窗口操作未完成。")?;
    let Some((expected, action)) = *pending else {
        return Ok(());
    };
    if expected != id {
        return Err("窗口操作已过期。".into());
    }
    *pending = None;
    drop(pending);
    if !saved {
        show_window(&app);
        return Ok(());
    }
    // The frontend has acknowledged the latest buffer's actual database commit.
    let core = state.core.clone();
    tauri::async_runtime::spawn_blocking(move || {
        core.invoke(json!({ "action": "foreground", "active": false }).to_string());
    });
    match action {
        Lifecycle::Hide => {
            if let Some(window) = app.get_webview_window("main") {
                window.hide().map_err(|_| "窗口隐藏失败。")?;
            }
        }
        Lifecycle::Quit => app.exit(0),
    }
    Ok(())
}

#[tauri::command]
fn open_external(app: tauri::AppHandle, url: String) -> Result<(), String> {
    if !(url.starts_with("https://") || url.starts_with("http://"))
        || url.chars().any(char::is_control)
        || url.contains(' ')
    {
        return Err("仅允许打开 HTTP 或 HTTPS 链接。".into());
    }
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|_| "无法打开系统浏览器。".into())
}

/// Same-directory hard-link publishes an already-synced file atomically without overwriting.
fn publish_new_file(target: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = target.parent().ok_or("请选择有效的导出目录。")?;
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| "无法创建导出文件。")?
        .as_nanos();
    let temporary = parent.join(format!(
        ".djhx-export-{}-{stamp}-{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    ));
    let operation = || -> std::io::Result<()> {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::hard_link(&temporary, target)?;
        Ok(())
    };
    let result = operation();
    let _ = fs::remove_file(temporary);
    result.map_err(|error| match error.kind() {
        std::io::ErrorKind::AlreadyExists => {
            "目标文件已存在，请使用新的文件名；已有文件未改动。".into()
        }
        std::io::ErrorKind::PermissionDenied => "没有权限写入所选目录，请重新选择。".into(),
        _ => "导出文件写入失败，请检查磁盘空间及目录是否支持原子创建。".into(),
    })
}

fn choose_and_save(
    app: &tauri::AppHandle,
    name: &str,
    extension: &str,
    bytes: &[u8],
) -> Result<Option<String>, String> {
    let chosen = app
        .dialog()
        .file()
        .set_file_name(name)
        .add_filter(extension, &[extension])
        .blocking_save_file();
    let Some(chosen) = chosen else {
        return Ok(None);
    };
    let path: PathBuf = chosen.into_path().map_err(|_| "请选择本地文件路径。")?;
    publish_new_file(&path, bytes)?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

#[tauri::command]
async fn export_notes(
    app: tauri::AppHandle,
    state: State<'_, Backend>,
    profile_id: String,
    include_deleted: bool,
    deleted_only: bool,
) -> Result<Option<String>, String> {
    let core = state.core.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let response = core.invoke(json!({ "action": "export", "profile_id": profile_id, "include_deleted": include_deleted, "deleted_only": deleted_only }).to_string());
        let response: Value = serde_json::from_str(&response).map_err(|_| "导出响应无效。")?;
        if response["ok"] != true { return Err(response.pointer("/error/message").and_then(Value::as_str).unwrap_or("核心导出失败。").to_owned()); }
        let bytes = BASE64_STANDARD.decode(response.pointer("/data/bytes_base64").and_then(Value::as_str).ok_or("导出内容缺失。")?).map_err(|_| "导出内容无效。")?;
        choose_and_save(&app, "djhx-memo-export.zip", "zip", &bytes)
    }).await.map_err(|_| "导出任务未完成。".to_string())?
}

#[tauri::command]
async fn rescue_text(app: tauri::AppHandle, content: String) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        choose_and_save(&app, "memo-rescue.md", "md", content.as_bytes())
    })
    .await
    .map_err(|_| "救援导出未完成。".to_string())?
}

#[cfg(target_os = "linux")]
fn tray_host_available() -> bool {
    // gdbus is supplied by libglib2.0-bin. Missing/unknown hosts mean normal window mode.
    std::process::Command::new("gdbus")
        .args([
            "call",
            "--timeout",
            "1",
            "--session",
            "--dest",
            "org.kde.StatusNotifierWatcher",
            "--object-path",
            "/StatusNotifierWatcher",
            "--method",
            "org.freedesktop.DBus.Properties.Get",
            "org.kde.StatusNotifierWatcher",
            "IsStatusNotifierHostRegistered",
        ])
        .output()
        .map(|result| {
            result.status.success() && String::from_utf8_lossy(&result.stdout).contains("true")
        })
        .unwrap_or(false)
}
#[cfg(not(target_os = "linux"))]
fn tray_host_available() -> bool {
    true
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            show_window(app)
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let root = app.path().app_data_dir()?;
            fs::create_dir_all(&root)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&root, fs::Permissions::from_mode(0o700))?;
            }
            let core = MemoApp::new(
                root.to_string_lossy().into_owned(),
                Box::new(SystemCredentials),
                cfg!(debug_assertions),
            )?;
            let open = MenuItem::with_id(app, "open", "打开主窗口", true, None::<&str>)?;
            let create = MenuItem::with_id(app, "new", "新建笔记", true, None::<&str>)?;
            let sync = MenuItem::with_id(app, "sync", "立即同步（仅本机）", false, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &create, &sync, &quit])?;
            let mut tray = TrayIconBuilder::with_id("memo")
                .menu(&menu)
                .tooltip("简记 · djhx memo")
                .show_menu_on_left_click(true)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_window(app),
                    "new" => {
                        show_window(app);
                        let _ = app.emit_to("main", "memo-new", ());
                    }
                    "sync" => {
                        let _ = app.emit_to("main", "memo-sync", ());
                    }
                    "quit" => request_lifecycle(app, Lifecycle::Quit),
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            let tray_available = tray.build(app).is_ok() && tray_host_available();
            app.manage(Backend {
                core,
                tray_available,
                next_lifecycle: AtomicU64::new(1),
                pending_lifecycle: Mutex::new(None),
                sync_item: sync,
                frontend_ready: AtomicBool::new(false),
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                request_lifecycle(window.app_handle(), Lifecycle::Hide);
            }
        })
        .invoke_handler(tauri::generate_handler![
            core_invoke,
            runtime_info,
            finish_lifecycle,
            open_external,
            export_notes,
            rescue_text,
            frontend_ready
        ])
        .run(tauri::generate_context!())
        .expect("应用启动失败；请检查本地数据目录权限与系统 WebView 运行环境。");
}

#[cfg(test)]
mod tests {
    use super::publish_new_file;
    use std::fs;

    #[test]
    fn exports_preserve_existing_files_and_leave_no_partial_temporary_file() {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root =
            std::env::temp_dir().join(format!("memo-export-test-{}-{stamp}", std::process::id()));
        fs::create_dir(&root).unwrap();
        let target = root.join("notes.zip");
        publish_new_file(&target, b"first committed archive").unwrap();
        assert!(publish_new_file(&target, b"replacement must not win").is_err());
        assert_eq!(fs::read(&target).unwrap(), b"first committed archive");
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        assert!(publish_new_file(&root.join("missing").join("notes.zip"), b"data").is_err());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
}
