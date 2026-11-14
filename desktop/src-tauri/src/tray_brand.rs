//! Tray artwork follows the system panel, independently of the app's forced theme.

#[cfg(windows)]
fn panel_dark() -> Option<bool> {
    use std::ffi::c_void;
    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn RegGetValueW(
            key: *mut c_void,
            subkey: *const u16,
            value: *const u16,
            flags: u32,
            kind: *mut u32,
            data: *mut c_void,
            size: *mut u32,
        ) -> i32;
    }
    let key: Vec<u16> = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\0"
        .encode_utf16()
        .collect();
    let value: Vec<u16> = "SystemUsesLightTheme\0".encode_utf16().collect();
    let mut light = 0u32;
    let mut size = 4u32;
    // A fixed presentation DWORD only; the output buffer is exactly four bytes.
    let status = unsafe {
        RegGetValueW(
            (-2147483647isize) as *mut c_void,
            key.as_ptr(),
            value.as_ptr(),
            0x10,
            std::ptr::null_mut(),
            (&mut light as *mut u32).cast(),
            &mut size,
        )
    };
    (status == 0 && size == 4 && light <= 1).then_some(light == 0)
}

#[cfg(not(windows))]
fn panel_dark() -> Option<bool> {
    // An explicit host setting is evidence; desktop environment names are not panel colours.
    match std::env::var("DJHX_MEMO_TRAY_THEME").as_deref() {
        Ok("dark") => Some(true),
        Ok("light") => Some(false),
        _ => None,
    }
}

pub fn image(app: &tauri::AppHandle) -> Option<tauri::image::Image<'static>> {
    if super::brand::MONO_TRAY
        && let Some(dark) = panel_dark()
    {
        let data: &[u8] = if dark {
            include_bytes!("../icons/tray-white.rgba")
        } else {
            include_bytes!("../icons/tray-black.rgba")
        };
        return Some(tauri::image::Image::new_owned(data.to_vec(), 32, 32));
    }
    app.default_window_icon().map(|icon| {
        tauri::image::Image::new_owned(icon.rgba().to_vec(), icon.width(), icon.height())
    })
}

pub fn watch(app: tauri::AppHandle) {
    // No external process, network request or UI-thread polling for theme changes.
    std::thread::spawn(move || {
        let mut previous = panel_dark();
        loop {
            std::thread::sleep(std::time::Duration::from_secs(10));
            let current = panel_dark();
            if current != previous {
                if let Some(tray) = app.tray_by_id("memo") {
                    let _ = tray.set_icon(image(&app));
                }
                previous = current;
            }
        }
    });
}
