use djhx_memo_core::UpdateService;
use serde_json::Value;
#[cfg(windows)]
use serde_json::json;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use tauri::{Manager, State};

pub(crate) struct Updates {
    pub service: Arc<UpdateService>,
    pub installing: AtomicBool,
}

#[tauri::command]
pub(crate) async fn update_invoke(
    window: tauri::WebviewWindow,
    state: State<'_, Updates>,
    action: String,
    auto_check: Option<bool>,
    download_policy: Option<String>,
) -> Result<String, String> {
    if window.label() != "main" {
        return Err("UPDATE_UNSUPPORTED".into());
    }
    if state.installing.load(Ordering::Acquire) && action != "snapshot" {
        return Ok(state.service.snapshot());
    }
    let service = state.service.clone();
    tauri::async_runtime::spawn_blocking(move || match action.as_str() {
        "snapshot" => service.snapshot(),
        "check" => service.check(true),
        "automatic" => {
            service.check(false);
            service.download(false, false)
        }
        "download" => service.download(true, false),
        "cancel" => {
            service.cancel();
            service.snapshot()
        }
        "defer" => service.defer(),
        "preferences" => service.preferences(
            auto_check.unwrap_or(true),
            download_policy.unwrap_or_else(|| "unmetered".into()),
        ),
        _ => service.snapshot(),
    })
    .await
    .map_err(|_| "UPDATE_STORAGE".into())
}

#[tauri::command]
pub(crate) async fn install_update(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
    state: State<'_, Updates>,
) -> Result<(), String> {
    if window.label() != "main" || !cfg!(windows) {
        return Err("UPDATE_UNSUPPORTED".into());
    }
    if state.installing.swap(true, Ordering::AcqRel) {
        return Err("UPDATE_BUSY".into());
    }
    let service = state.service.clone();
    let core = app.state::<super::Backend>().core.clone();
    let reset_service = service.clone();
    let reset_core = core.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let result: Value =
            serde_json::from_str(&service.prepare_install()).map_err(|_| "UPDATE_STORAGE")?;
        if result["ok"] != true {
            return Err(result["error"]
                .as_str()
                .unwrap_or("UPDATE_INSTALL")
                .to_string());
        }
        core.pause_for_update()
            .map_err(|_| "UPDATE_SAVE".to_string())?;
        install(&app, &result["data"])
    })
    .await
    .unwrap_or_else(|_| Err("UPDATE_INSTALL".into()));
    // On Windows successful handoff exits the process. All returned paths must unlock editing.
    reset_core.resume_after_update();
    reset_service.installation_failed();
    state.installing.store(false, Ordering::Release);
    result
}

#[cfg(windows)]
fn install(app: &tauri::AppHandle, prepared: &Value) -> Result<(), String> {
    use tauri_plugin_updater::UpdaterExt;
    let r = &prepared["release"];
    check_installer_version(
        prepared["path"].as_str().ok_or("UPDATE_PACKAGE")?,
        r["version"].as_str().ok_or("UPDATE_PACKAGE")?,
    )?;
    let updater = app
        .updater_builder()
        .pubkey(
            prepared["package_public_key"]
                .as_str()
                .ok_or("UPDATE_SIGNATURE")?,
        )
        .restart_after_install(true)
        .build()
        .map_err(|_| "UPDATE_INSTALL")?;
    let update = updater
        .restore_update(json!({"version":r["version"],"notes":r["notes"],
        "url":r["url"],"signature":r["signature"]}))
        .map_err(|_| "UPDATE_SIGNATURE")?
        .ok_or("UPDATE_CHANGED")?;
    let bytes = std::fs::read(prepared["path"].as_str().ok_or("UPDATE_PACKAGE")?)
        .map_err(|_| "UPDATE_PACKAGE")?;
    update.install(&bytes).map_err(|_| "UPDATE_INSTALL".into())
}

#[cfg(windows)]
fn check_installer_version(path: &str, expected: &str) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "version")]
    unsafe extern "system" {
        fn GetFileVersionInfoSizeW(path: *const u16, handle: *mut u32) -> u32;
        fn GetFileVersionInfoW(
            path: *const u16,
            handle: u32,
            length: u32,
            data: *mut std::ffi::c_void,
        ) -> i32;
        fn VerQueryValueW(
            data: *const std::ffi::c_void,
            sub: *const u16,
            result: *mut *mut std::ffi::c_void,
            length: *mut u32,
        ) -> i32;
    }
    let path: Vec<u16> = std::ffi::OsStr::new(path)
        .encode_wide()
        .chain(Some(0))
        .collect();
    let mut ignored = 0;
    // Windows parses the PE resource; neither this call nor the updater executes it before verification.
    let size = unsafe { GetFileVersionInfoSizeW(path.as_ptr(), &mut ignored) };
    if !(52..=1_048_576).contains(&size) {
        return Err("UPDATE_PACKAGE".into());
    }
    let mut data = vec![0u8; size as usize];
    let mut info = std::ptr::null_mut();
    let mut length = 0;
    let root = [b'\\' as u16, 0];
    let ok = unsafe {
        GetFileVersionInfoW(path.as_ptr(), 0, size, data.as_mut_ptr().cast()) != 0
            && VerQueryValueW(data.as_ptr().cast(), root.as_ptr(), &mut info, &mut length) != 0
    };
    if !ok || info.is_null() || length < 52 {
        return Err("UPDATE_PACKAGE".into());
    }
    // VS_FIXEDFILEINFO starts with signature, structure version, file-version MS and LS.
    let words: [u32; 4] = unsafe { std::ptr::read_unaligned(info.cast()) };
    let actual = format!(
        "{}.{}.{}",
        words[2] >> 16,
        words[2] & 0xffff,
        words[3] >> 16
    );
    if words[0] != 0xFEEF04BD || words[3] & 0xffff != 0 || actual != expected {
        return Err("UPDATE_PACKAGE".into());
    }
    Ok(())
}
#[cfg(not(windows))]
fn install(_: &tauri::AppHandle, _: &Value) -> Result<(), String> {
    Err("UPDATE_UNSUPPORTED".into())
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use tauri_plugin_updater::UpdaterExt;

    #[test]
    #[ignore = "requires DJHX_TEST_INSTALLER pointing to an actually signed NSIS build"]
    fn signed_nsis_matches_native_version_and_tauri_restore_verification() {
        let path = std::env::var("DJHX_TEST_INSTALLER").unwrap();
        check_installer_version(&path, env!("CARGO_PKG_VERSION")).unwrap();
        assert!(check_installer_version(&path, "9.9.9").is_err());
        let trust: Value =
            serde_json::from_str(include_str!("../../../config/update-trust.json")).unwrap();
        let key = trust["package_keys"]
            .as_object()
            .unwrap()
            .values()
            .next()
            .unwrap()
            .as_str()
            .unwrap();
        let mut context = tauri::test::mock_context(tauri::test::noop_assets());
        context.config_mut().plugins.0.insert(
            "updater".into(),
            json!({"pubkey":key,"requireSignedVersion":true,"endpoints":["https://memo.djhx.site/updates/stable/current.json"]}),
        );
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_updater::Builder::new().pubkey(key).build())
            .build(context)
            .unwrap();
        let updater = app.updater_builder().pubkey(key).build().unwrap();
        let signature = std::fs::read_to_string(format!("{path}.sig")).unwrap();
        let update = updater.restore_update(json!({"version":env!("CARGO_PKG_VERSION"),
            "url":"https://github.com/Koril33/djhx-memo/releases/download/v0.1.5/test.exe","signature":signature})).unwrap().unwrap();
        let bytes = std::fs::read(path).unwrap();
        update.verify(&bytes).unwrap();
        assert!(update.verify(b"corrupted installer").is_err());
        let wrong_version = updater
            .restore_update(
                json!({"version":"9.9.9", "url":update.download_url, "signature":update.signature}),
            )
            .unwrap()
            .unwrap();
        assert!(wrong_version.verify(&bytes).is_err());
    }
}
