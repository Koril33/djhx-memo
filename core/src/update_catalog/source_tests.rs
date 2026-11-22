use super::*;

#[test]
fn fixed_mirror_maps_only_the_canonical_signed_asset_identity() {
    let (trust, bytes, _) = super::tests::fixture();
    let (catalog, _, _) = decode(&bytes, &trust, super::tests::now()).unwrap();
    let mut release = catalog.targets["windows-x86_64-nsis"].clone();
    let file = format!("djhx-memo-{}-windows-x64-setup.exe", release.version);
    release.url = format!(
        "https://github.com/Koril33/djhx-memo/releases/download/{}/{file}",
        release.tag
    );
    validate_release(&release, "windows-x86_64-nsis", "app.djhx.memo", &trust).unwrap();
    assert_eq!(
        download_url(&release, "windows-x86_64-nsis", DownloadSource::Memo).unwrap(),
        format!(
            "https://memo.djhx.site/downloads/releases/{}/{file}",
            release.tag
        )
    );
    assert_eq!(
        download_url(&release, "windows-x86_64-nsis", DownloadSource::Github).unwrap(),
        release.url
    );
    for invalid in [
        release.url.replace("Koril33", "other"),
        format!("{}?mirror=evil", release.url),
        format!("{}#part", release.url),
        release.url.replace("https://", "http://"),
        release.url.replace(&file, "test.exe"),
        release.url.replace(&file, &format!("%2f{file}")),
        release.url.replace("github.com", "github.com.evil.test"),
        release.url.replace(&file, &format!("../{file}")),
    ] {
        let mut changed = release.clone();
        changed.url = invalid;
        assert!(download_url(&changed, "windows-x86_64-nsis", DownloadSource::Memo).is_err());
    }
    assert!(download_url(&release, "android-universal-apk", DownloadSource::Memo).is_err());
}

#[test]
fn source_preference_survives_restart_without_touching_the_replay_ledger_or_cache() {
    let root = super::tests::root();
    let (trust, _, _) = super::tests::fixture();
    let open = || {
        UpdateService::open(
            root.clone(),
            "system-managed".into(),
            "0.2.1".into(),
            0,
            "app.djhx.memo".into(),
            trust.clone(),
        )
    };
    let service = open();
    assert_eq!(service.state.lock().unwrap().source, DownloadSource::Memo);
    let saved = Saved {
        sequence: 99,
        digest: "verified".into(),
        deferred: "0.2.2".into(),
        ..Saved::default()
    };
    service.persist(&saved).unwrap();
    let ledger = fs::read(root.join("state.json")).unwrap();
    fs::write(service.package_path(), b"verified cached bytes").unwrap();
    service.set_download_source("github".into());
    assert!(
        service
            .manual_download_url()
            .starts_with("https://github.com/")
    );
    assert_eq!(fs::read(root.join("state.json")).unwrap(), ledger);
    assert_eq!(
        fs::read(service.package_path()).unwrap(),
        b"verified cached bytes"
    );
    let reopened = open();
    assert_eq!(
        reopened.state.lock().unwrap().source,
        DownloadSource::Github
    );
    assert_eq!(reopened.state.lock().unwrap().saved.sequence, 99);
    reopened.set_download_source("https://evil.test".into());
    assert_eq!(
        reopened.state.lock().unwrap().source,
        DownloadSource::Github
    );
    assert_eq!(
        reopened.state.lock().unwrap().source_error,
        Some("UPDATE_SOURCE")
    );
    fs::write(root.join("transport-preferences.json"), b"broken").unwrap();
    let recovered = open();
    let state = recovered.state.lock().unwrap();
    assert_eq!(state.source, DownloadSource::Memo);
    assert_eq!(state.source_error, Some("UPDATE_SOURCE"));
    assert_eq!(state.saved.sequence, 99);
    assert!(check_replay(&state.saved, 98, "old").is_err());
    drop(state);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn switching_cancels_and_waits_for_the_native_receipt_before_changing_source() {
    let root = super::tests::root();
    let (trust, _, _) = super::tests::fixture();
    let service = Arc::new(UpdateService::open(
        root.clone(),
        "system-managed".into(),
        "0.2.1".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    ));
    let operation = service.operation.lock().unwrap();
    let cloned = service.clone();
    let thread = std::thread::spawn(move || cloned.set_download_source("github".into()));
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while !service.source_changing.load(Ordering::Acquire) {
        assert!(std::time::Instant::now() < deadline);
        std::thread::yield_now();
    }
    assert_eq!(service.state.lock().unwrap().source, DownloadSource::Memo);
    assert!(!root.join("transport-preferences.json").exists());
    service.download(true, false);
    drop(operation);
    thread.join().unwrap();
    assert!(service.canceled.load(Ordering::Acquire));
    assert_eq!(service.state.lock().unwrap().source, DownloadSource::Github);
    assert!(!service.source_changing.load(Ordering::Acquire));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn source_switch_cannot_repair_corrupt_security_state_or_change_preparing_install() {
    let root = super::tests::root();
    let (trust, _, _) = super::tests::fixture();
    fs::write(root.join("state.json"), b"corrupt security state").unwrap();
    let service = UpdateService::open(
        root.clone(),
        "system-managed".into(),
        "0.2.1".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    );
    service.set_download_source("github".into());
    assert!(service.snapshot().contains("UPDATE_STORAGE"));
    assert!(!root.join("transport-preferences.json").exists());
    assert_eq!(
        fs::read(root.join("state.json")).unwrap(),
        b"corrupt security state"
    );
    fs::remove_dir_all(root).unwrap();

    let root = super::tests::root();
    let (trust, _, _) = super::tests::fixture();
    let service = UpdateService::open(
        root.clone(),
        "system-managed".into(),
        "0.2.1".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    );
    service.state.lock().unwrap().phase = "preparing";
    service.set_download_source("github".into());
    let state = service.state.lock().unwrap();
    assert_eq!(state.phase, "preparing");
    assert_eq!(state.source, DownloadSource::Memo);
    assert_eq!(state.source_error, Some("UPDATE_BUSY"));
    assert!(!root.join("transport-preferences.json").exists());
    assert!(!service.source_changing.load(Ordering::Acquire));
    drop(state);
    fs::remove_dir_all(root).unwrap();
}
