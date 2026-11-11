use super::*;
use serde_json::Value;

fn fixture() -> (Trust, Vec<u8>, Vec<u8>) {
    let f: Value =
        serde_json::from_str(include_str!("../../tests/fixtures/update/signed.json")).unwrap();
    (
        serde_json::from_value(f["trust"].clone()).unwrap(),
        serde_json::to_vec(&f["envelope"]).unwrap(),
        BASE64_STANDARD
            .decode(f["package_base64"].as_str().unwrap())
            .unwrap(),
    )
}
fn now() -> i64 {
    DateTime::parse_from_rfc3339("2026-11-13T00:00:00Z")
        .unwrap()
        .timestamp()
}

#[test]
fn streamed_download_rejects_truncation_overrun_wrong_bytes_cancel_and_disk_failure() {
    let (trust, bytes, package) = fixture();
    let (catalog, _, _) = decode(&bytes, &trust, now()).unwrap();
    let r = &catalog.targets["windows-x86_64-nsis"];
    let canceled = AtomicBool::new(false);
    let mut out = Vec::new();
    copy_verified(&mut package.as_slice(), &mut out, r, &canceled, |_| Ok(())).unwrap();
    assert_eq!(out, package);
    for wrong in [
        package[..package.len() - 1].to_vec(),
        [package.clone(), vec![0]].concat(),
        vec![0; package.len()],
    ] {
        assert_eq!(
            copy_verified(
                &mut wrong.as_slice(),
                &mut Vec::new(),
                r,
                &canceled,
                |_| Ok(())
            ),
            Err("UPDATE_PACKAGE")
        );
    }
    canceled.store(true, Ordering::Release);
    assert_eq!(
        copy_verified(
            &mut package.as_slice(),
            &mut Vec::new(),
            r,
            &canceled,
            |_| Ok(())
        ),
        Err("UPDATE_CANCELED")
    );
    struct FullDisk;
    impl Write for FullDisk {
        fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
            Err(std::io::ErrorKind::StorageFull.into())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    canceled.store(false, Ordering::Release);
    assert_eq!(
        copy_verified(
            &mut package.as_slice(),
            &mut FullDisk,
            r,
            &canceled,
            |_| Ok(())
        ),
        Err("UPDATE_STORAGE")
    );
}

#[test]
fn accepted_sequence_cannot_go_back_or_change_content_at_the_same_sequence() {
    let saved = Saved {
        sequence: 7,
        digest: "accepted".into(),
        ..Saved::default()
    };
    assert_eq!(check_replay(&saved, 6, "accepted"), Err("UPDATE_REPLAY"));
    assert_eq!(check_replay(&saved, 7, "modified"), Err("UPDATE_REPLAY"));
    assert!(check_replay(&saved, 7, "accepted").is_ok());
    assert!(check_replay(&saved, 8, "new signed catalog").is_ok());
}

#[test]
fn startup_removes_only_its_own_crashed_staging_files() {
    let root = root();
    let partial = root.join(format!("package-{}.part", uuid::Uuid::new_v4()));
    fs::write(&partial, b"incomplete download").unwrap();
    fs::write(root.join("package.bin"), b"cached asset").unwrap();
    fs::write(root.join("state.json"), b"keep authoritative state").unwrap();
    fs::write(
        root.join("package-other.part"),
        b"not an owned staging name",
    )
    .unwrap();
    clean_staging(&root).unwrap();
    assert!(!partial.exists());
    assert_eq!(fs::read_dir(&root).unwrap().count(), 3);
    assert_eq!(
        fs::read(root.join("state.json")).unwrap(),
        b"keep authoritative state"
    );
    fs::remove_dir_all(root).unwrap();
}
fn root() -> PathBuf {
    let p = std::env::temp_dir().join(format!("memo-updater-{}", uuid::Uuid::new_v4()));
    fs::create_dir(&p).unwrap();
    p
}

#[test]
fn real_tauri_signer_catalog_and_package_encodings_verify() {
    let (trust, bytes, package) = fixture();
    let (catalog, _, sequence) = decode(&bytes, &trust, now()).unwrap();
    assert_eq!(sequence, 1);
    let release = catalog.targets.get("windows-x86_64-nsis").unwrap();
    validate_release(release, "windows-x86_64-nsis", "app.djhx.memo", &trust).unwrap();
    let root = root();
    let service = UpdateService::open(
        root.clone(),
        "windows-x86_64-nsis".into(),
        "0.1.5".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    );
    fs::write(service.package_path(), &package).unwrap();
    service.verify_file(release).unwrap();
    fs::write(service.package_path(), b"modified payload").unwrap();
    assert_eq!(service.verify_file(release), Err("UPDATE_PACKAGE"));
    fs::write(service.package_path(), &package).unwrap();
    let mut different_version = release.clone();
    different_version.version = "0.1.7".into();
    assert_eq!(
        service.verify_file(&different_version),
        Err("UPDATE_SIGNATURE")
    );
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn catalog_tampering_unknown_keys_wrong_clock_and_oversize_fail_closed() {
    let (trust, bytes, _) = fixture();
    let mut envelope: Value = serde_json::from_slice(&bytes).unwrap();
    envelope["key_id"] = json!("untrusted");
    assert!(matches!(
        decode(&serde_json::to_vec(&envelope).unwrap(), &trust, now()),
        Err("UPDATE_SIGNATURE")
    ));
    envelope = serde_json::from_slice(&bytes).unwrap();
    envelope["payload_base64"] = json!(BASE64_STANDARD.encode(b"tampered"));
    assert!(matches!(
        decode(&serde_json::to_vec(&envelope).unwrap(), &trust, now()),
        Err("UPDATE_SIGNATURE")
    ));
    assert!(matches!(
        decode(&bytes, &trust, now() + 60 * 86400),
        Err("UPDATE_EXPIRED")
    ));
    assert!(matches!(
        decode(&bytes, &trust, now() - 60 * 86400),
        Err("UPDATE_EXPIRED")
    ));
    assert!(matches!(
        decode(&vec![b' '; MAX_FEED as usize + 1], &trust, now()),
        Err("UPDATE_CATALOG")
    ));
}

#[test]
fn platform_protocol_schema_version_and_immutable_url_are_bound() {
    let (trust, bytes, _) = fixture();
    let (catalog, _, _) = decode(&bytes, &trust, now()).unwrap();
    let original = catalog.targets["windows-x86_64-nsis"].clone();
    for url in [
        "http://github.com/Koril33/djhx-memo/releases/download/v0.1.6/test.exe",
        "https://github.com/other/repo/releases/download/v0.1.6/test.exe",
        "https://github.com/Koril33/djhx-memo/releases/latest/download/test.exe",
        "https://user@github.com/Koril33/djhx-memo/releases/download/v0.1.6/test.exe",
    ] {
        let mut r = original.clone();
        r.url = url.into();
        assert_eq!(
            validate_release(&r, "windows-x86_64-nsis", "app.djhx.memo", &trust),
            Err("UPDATE_CATALOG")
        );
    }
    let mut r = original.clone();
    r.schema_max = 4;
    assert_eq!(
        validate_release(&r, "windows-x86_64-nsis", "app.djhx.memo", &trust),
        Err("UPDATE_INCOMPATIBLE")
    );
    r = original.clone();
    r.protocols = vec![1];
    assert_eq!(
        validate_release(&r, "windows-x86_64-nsis", "app.djhx.memo", &trust),
        Err("UPDATE_INCOMPATIBLE")
    );
    r = original.clone();
    r.version = "0.1.6-beta.1".into();
    assert_eq!(
        validate_release(&r, "windows-x86_64-nsis", "app.djhx.memo", &trust),
        Err("UPDATE_CATALOG")
    );
    assert_eq!(
        validate_release(&original, "linux-managed", "app.djhx.memo", &trust),
        Err("UPDATE_UNSUPPORTED")
    );
}

#[test]
fn duplicate_targets_and_struct_fields_are_rejected() {
    let (trust, bytes, _) = fixture();
    let (catalog, _, _) = decode(&bytes, &trust, now()).unwrap();
    let r = serde_json::to_string(&catalog.targets["windows-x86_64-nsis"]).unwrap();
    let duplicate = format!(
        r#"{{"schema_version":1,"application_id":"app.djhx.memo","channel":"stable","sequence":"1","issued_at":"x","expires_at":"x","targets":{{"windows-x86_64-nsis":{r},"windows-x86_64-nsis":{r}}}}}"#
    );
    assert!(serde_json::from_str::<Catalog>(&duplicate).is_err());
    assert!(serde_json::from_str::<Envelope>(r#"{"envelope_version":1,"envelope_version":1,"key_id":"x","payload_base64":"","signature":""}"#).is_err());
}

#[test]
fn corrupt_state_cannot_be_reset_by_preferences_or_defer_and_notes_are_untouched() {
    let (trust, _, _) = fixture();
    let root = root();
    fs::write(root.join("state.json"), b"truncated").unwrap();
    let service = UpdateService::open(
        root.clone(),
        "windows-x86_64-nsis".into(),
        "0.1.5".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    );
    service.preferences(true, "any".into());
    service.defer();
    assert_eq!(fs::read(root.join("state.json")).unwrap(), b"truncated");
    assert!(service.snapshot().contains("UPDATE_STORAGE"));
    assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn replay_state_and_attempt_survive_reopen_without_changing_preferences() {
    let (trust, _, _) = fixture();
    let root = root();
    let service = UpdateService::open(
        root.clone(),
        "windows-x86_64-nsis".into(),
        "0.1.5".into(),
        0,
        "app.djhx.memo".into(),
        trust.clone(),
    );
    let saved = Saved {
        sequence: 99,
        digest: "verified".into(),
        auto_check: false,
        download_policy: "notify".into(),
        attempted: Some("0.1.6".into()),
        ..Saved::default()
    };
    service.persist(&saved).unwrap();
    let reopened = UpdateService::open(
        root.clone(),
        "windows-x86_64-nsis".into(),
        "0.1.6".into(),
        0,
        "app.djhx.memo".into(),
        trust,
    );
    let state = reopened.state.lock().unwrap();
    assert_eq!(state.phase, "completed");
    assert_eq!(state.saved.sequence, 99);
    assert!(!state.saved.auto_check);
    fs::remove_dir_all(root).unwrap();
}
