//! Anonymous, signed application distribution. Never shares account HTTP state.
use base64::{Engine as _, prelude::BASE64_STANDARD};
use chrono::{DateTime, Utc};
use minisign_verify::{PublicKey, Signature};
use semver::Version;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

const MAX_FEED: u64 = 1_048_576;
const MAX_PACKAGE: u64 = 512 * 1_048_576;
const TRUST: &str = include_str!("../../config/update-trust.json");
type Result<T> = std::result::Result<T, &'static str>;

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Trust {
    feed: String,
    catalog_keys: BTreeMap<String, String>,
    package_keys: BTreeMap<String, String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    envelope_version: u32,
    key_id: String,
    payload_base64: String,
    signature: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Catalog {
    schema_version: u32,
    application_id: String,
    channel: String,
    sequence: String,
    issued_at: String,
    expires_at: String,
    #[serde(deserialize_with = "unique_targets")]
    targets: BTreeMap<String, Release>,
    #[serde(default)]
    withdrawn: Vec<String>,
}

// serde rejects duplicate struct fields; map keys need the same explicit rule.
fn unique_targets<'de, D: serde::Deserializer<'de>>(
    d: D,
) -> std::result::Result<BTreeMap<String, Release>, D::Error> {
    struct Unique;
    impl<'de> serde::de::Visitor<'de> for Unique {
        type Value = BTreeMap<String, Release>;
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
            f.write_str("unique platform targets")
        }
        fn visit_map<A: serde::de::MapAccess<'de>>(
            self,
            mut map: A,
        ) -> std::result::Result<Self::Value, A::Error> {
            let mut values = BTreeMap::new();
            while let Some((key, value)) = map.next_entry::<String, Release>()? {
                if values.insert(key, value).is_some() {
                    return Err(serde::de::Error::custom("duplicate target"));
                }
            }
            Ok(values)
        }
    }
    d.deserialize_map(Unique)
}

/// Immutable asset identity, returned only to trusted native platform adapters.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Release {
    /// Stable SemVer without a leading v.
    pub version: String,
    /// Immutable Git tag, v followed by version.
    pub tag: String,
    /// Full source commit ID.
    pub commit: String,
    /// Plain-text release notes.
    pub notes: String,
    /// Immutable HTTPS GitHub asset URL.
    pub url: String,
    /// Exact number of artifact bytes.
    pub size: u64,
    /// Lower-case hex SHA-256 of the artifact.
    pub sha256: String,
    /// Desktop signature in Tauri's .sig encoding.
    pub signature: Option<String>,
    /// Locally pinned package signing key ID.
    pub package_key_id: Option<String>,
    /// Supported sync protocol versions.
    pub protocols: Vec<u32>,
    /// Minimum readable/writable local database schema.
    pub schema_min: u32,
    /// Maximum readable/writable local database schema.
    pub schema_max: u32,
    /// Updater format generation, currently 1.
    pub min_updater: u32,
    /// Android package ID; absent on desktop.
    pub android: Option<AndroidRelease>,
}
/// APK identity prechecked by PackageManager and finally by the system installer.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct AndroidRelease {
    /// Must match the running installation.
    pub application_id: String,
    /// Strictly larger than the installed version code.
    pub version_code: u64,
    /// Minimum Android SDK level.
    pub min_sdk: u32,
    /// SHA-256 of the unchanged release certificate.
    pub certificate_sha256: String,
    /// Supported native ABIs in the universal APK.
    pub abis: Vec<String>,
}

#[derive(Clone, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Saved {
    sequence: u64,
    digest: String,
    last_checked: i64,
    #[serde(default = "enabled")]
    auto_check: bool,
    #[serde(default = "default_download")]
    download_policy: String,
    deferred: String,
    attempted: Option<String>,
}
fn enabled() -> bool {
    true
}
fn default_download() -> String {
    "unmetered".into()
}
struct Runtime {
    saved: Saved,
    phase: &'static str,
    candidate: Option<Release>,
    downloaded: u64,
    error: Option<&'static str>,
}

/// Per-device updater, isolated from notes, accounts and synchronization.
#[derive(uniffi::Object)]
pub struct UpdateService {
    root: PathBuf,
    target: String,
    version: String,
    version_code: u64,
    application_id: String,
    trust: Trust,
    state: Mutex<Runtime>,
    operation: Mutex<()>,
    canceled: AtomicBool,
}

#[uniffi::export]
impl UpdateService {
    /// Opens updater state; corrupt state disables updates without affecting notes.
    #[uniffi::constructor]
    pub fn new(
        root: String,
        target: String,
        version: String,
        version_code: u64,
        application_id: String,
    ) -> Arc<Self> {
        let trust = serde_json::from_str(TRUST).expect("embedded update trust must be valid");
        Arc::new(Self::open(
            PathBuf::from(root),
            target,
            version,
            version_code,
            application_id,
            trust,
        ))
    }

    /// Returns public UI state; contains no credentials or file-system paths.
    pub fn snapshot(&self) -> String {
        let Ok(s) = self.state.lock() else {
            return json!({"phase":"failed","error":"UPDATE_STORAGE"}).to_string();
        };
        json!({"phase":s.phase,"candidate":s.candidate,"downloaded":s.downloaded,"error":s.error,
            "configured":!self.trust.catalog_keys.is_empty(),"current_version":self.version,"target":self.target,
            "last_checked":s.saved.last_checked,"auto_check":s.saved.auto_check,
            "download_policy":s.saved.download_policy,"deferred":s.saved.deferred}).to_string()
    }

    /// Checks the signed static feed. False respects the daily device preference.
    pub fn check(&self, manual: bool) -> String {
        if !matches!(
            self.target.as_str(),
            "windows-x86_64-nsis" | "android-universal-apk"
        ) {
            self.fail("UPDATE_UNSUPPORTED");
            return self.snapshot();
        }
        let Ok(_op) = self.operation.try_lock() else {
            return self.snapshot();
        };
        if !manual {
            let due = self.state.lock().is_ok_and(|s| {
                s.saved.auto_check && Utc::now().timestamp() - s.saved.last_checked >= 86400
            });
            if !due {
                return self.snapshot();
            }
        }
        self.run("checking", || self.fetch_catalog());
        self.snapshot()
    }

    /// Downloads into a private file with bounded streaming and full verification.
    pub fn download(&self, manual: bool, unmetered: bool) -> String {
        let Ok(_op) = self.operation.try_lock() else {
            return self.snapshot();
        };
        if !manual
            && self
                .state
                .lock()
                .is_ok_and(|s| matches!(s.phase, "ready" | "preparing"))
        {
            return self.snapshot();
        }
        if !manual {
            let allowed = self.state.lock().is_ok_and(|s| {
                s.saved.auto_check
                    && s.candidate
                        .as_ref()
                        .is_some_and(|r| r.version != s.saved.deferred)
                    && (s.saved.download_policy == "any"
                        || s.saved.download_policy == "unmetered" && unmetered)
            });
            if !allowed {
                return self.snapshot();
            }
        }
        self.canceled.store(false, Ordering::Release);
        self.run("downloading", || self.download_inner());
        self.snapshot()
    }

    /// Cancels a streaming download at the next chunk; partial files are not installed.
    pub fn cancel(&self) {
        self.canceled.store(true, Ordering::Release);
    }

    /// Persists device-only preferences. Invalid policies are rejected without mutation.
    pub fn preferences(&self, auto_check: bool, download_policy: String) -> String {
        if !matches!(download_policy.as_str(), "notify" | "unmetered" | "any") {
            return self.snapshot();
        }
        if let Ok(mut s) = self.state.lock() {
            if s.error == Some("UPDATE_STORAGE") {
                drop(s);
                return self.snapshot();
            }
            let old = s.saved.clone();
            s.saved.auto_check = auto_check;
            s.saved.download_policy = download_policy;
            if self.persist(&s.saved).is_err() {
                s.saved = old;
                s.error = Some("UPDATE_STORAGE");
            }
        }
        self.snapshot()
    }

    /// Suppresses background downloading for the offered version, retaining manual access.
    pub fn defer(&self) -> String {
        if let Ok(mut s) = self.state.lock() {
            if s.error == Some("UPDATE_STORAGE") {
                drop(s);
                return self.snapshot();
            }
            s.saved.deferred = s
                .candidate
                .as_ref()
                .map_or(String::new(), |r| r.version.clone());
            if self.persist(&s.saved).is_err() {
                s.error = Some("UPDATE_STORAGE");
            }
        }
        self.snapshot()
    }

    /// Revalidates the live catalog and cached package immediately before native installation.
    /// Returned JSON is native-only: platforms must not expose this method to Markdown/UI IPC.
    pub fn prepare_install(&self) -> String {
        let result = (|| {
            let _op = self.operation.try_lock().map_err(|_| "UPDATE_BUSY")?;
            let prior = self.candidate()?;
            self.fetch_catalog()?;
            if self.candidate()? != prior {
                return Err("UPDATE_CHANGED");
            }
            self.verify_file(&prior)?;
            let mut s = self.state.lock().map_err(|_| "UPDATE_STORAGE")?;
            s.saved.attempted = Some(prior.version.clone());
            self.persist(&s.saved)?;
            s.phase = "preparing";
            let key = prior
                .package_key_id
                .as_ref()
                .and_then(|k| self.trust.package_keys.get(k));
            Ok(json!({"path":self.package_path(),"release":prior,"package_public_key":key}))
        })();
        match result {
            Ok(data) => json!({"ok":true,"data":data}).to_string(),
            Err(code) => {
                self.fail(code);
                json!({"ok":false,"error":code}).to_string()
            }
        }
    }

    /// Marks a canceled/failed handoff; successful installs are confirmed on next startup.
    pub fn installation_failed(&self) {
        self.fail("UPDATE_INSTALL");
    }
}

/// Validates a signed publishing artifact using the same pinned roots and platform rules as clients.
/// Returns a decimal catalog sequence or a fixed, non-sensitive error code.
pub fn validate_published_catalog(envelope: &[u8]) -> std::result::Result<String, String> {
    let trust: Trust = serde_json::from_str(TRUST).map_err(|_| "UPDATE_CATALOG")?;
    let (catalog, _, sequence) =
        decode(envelope, &trust, Utc::now().timestamp()).map_err(str::to_owned)?;
    for (target, release) in &catalog.targets {
        validate_release(release, target, "app.djhx.memo", &trust).map_err(str::to_owned)?;
    }
    Ok(sequence.to_string())
}

/// Checks a local publishing asset against signed size/hash and the pinned Windows package key.
/// APK certificate/manifest identity additionally requires native Android/build-tools verification.
pub fn validate_published_asset(
    envelope: &[u8],
    target: &str,
    path: &Path,
) -> std::result::Result<(), String> {
    let trust: Trust = serde_json::from_str(TRUST).map_err(|_| "UPDATE_CATALOG")?;
    let (catalog, _, _) =
        decode(envelope, &trust, Utc::now().timestamp()).map_err(str::to_owned)?;
    let release = catalog.targets.get(target).ok_or("UPDATE_UNAVAILABLE")?;
    validate_release(release, target, "app.djhx.memo", &trust).map_err(str::to_owned)?;
    let mut file = File::open(path).map_err(|_| "UPDATE_STORAGE")?;
    copy_verified(
        &mut file,
        &mut std::io::sink(),
        release,
        &AtomicBool::new(false),
        |_| Ok(()),
    )
    .map_err(str::to_owned)?;
    verify_package_signature(path, release, target, &trust).map_err(str::to_owned)
}

impl UpdateService {
    fn open(
        root: PathBuf,
        target: String,
        version: String,
        version_code: u64,
        application_id: String,
        trust: Trust,
    ) -> Self {
        let saved_path = root.join("state.json");
        let loaded: Result<Saved> = (|| {
            fs::create_dir_all(&root).map_err(|_| "UPDATE_STORAGE")?;
            clean_staging(&root)?;
            if saved_path.exists() {
                serde_json::from_slice(&fs::read(saved_path).map_err(|_| "UPDATE_STORAGE")?)
                    .map_err(|_| "UPDATE_STORAGE")
            } else {
                Ok(Saved {
                    auto_check: true,
                    download_policy: default_download(),
                    ..Saved::default()
                })
            }
        })();
        let error = loaded.as_ref().err().copied();
        let mut saved = loaded.unwrap_or_default();
        let phase = if error.is_some() {
            "failed"
        } else if saved.attempted.as_deref() == Some(&version) {
            saved.attempted = None;
            "completed"
        } else {
            "idle"
        };
        Self {
            root,
            target,
            version,
            version_code,
            application_id,
            trust,
            state: Mutex::new(Runtime {
                saved,
                phase,
                candidate: None,
                downloaded: 0,
                error,
            }),
            operation: Mutex::new(()),
            canceled: AtomicBool::new(false),
        }
    }
    fn candidate(&self) -> Result<Release> {
        self.state
            .lock()
            .map_err(|_| "UPDATE_STORAGE")?
            .candidate
            .clone()
            .ok_or("UPDATE_UNAVAILABLE")
    }
    fn package_path(&self) -> PathBuf {
        self.root.join("package.bin")
    }
    fn persist(&self, saved: &Saved) -> Result<()> {
        let path = self
            .root
            .join(format!("state-{}.tmp", uuid::Uuid::new_v4()));
        let bytes = serde_json::to_vec(saved).map_err(|_| "UPDATE_STORAGE")?;
        let result = (|| {
            let mut file = private_file(&path)?;
            file.write_all(&bytes).map_err(|_| "UPDATE_STORAGE")?;
            file.sync_all().map_err(|_| "UPDATE_STORAGE")?;
            drop(file);
            fs::rename(&path, self.root.join("state.json")).map_err(|_| "UPDATE_STORAGE")
        })();
        let _ = fs::remove_file(path);
        result
    }
    fn fail(&self, code: &'static str) {
        if let Ok(mut s) = self.state.lock() {
            s.phase = if code == "UPDATE_CANCELED" {
                "canceled"
            } else {
                "failed"
            };
            s.error = Some(code);
        }
    }
    fn run(&self, phase: &'static str, f: impl FnOnce() -> Result<()>) {
        if let Ok(mut s) = self.state.lock() {
            if s.error == Some("UPDATE_STORAGE") {
                return;
            }
            s.phase = phase;
            s.error = None;
            if phase == "downloading" {
                s.downloaded = 0;
            }
        }
        if let Err(e) = f() {
            self.fail(e);
        }
    }
    fn client(&self) -> Result<reqwest::blocking::Client> {
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|_| "UPDATE_NETWORK")?
        .with_root_certificates(rustls::RootCertStore::from_iter(
            webpki_roots::TLS_SERVER_ROOTS.iter().cloned(),
        ))
        .with_no_client_auth();
        reqwest::blocking::Client::builder()
            .use_preconfigured_tls(tls)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(600))
            .user_agent(format!("djhx-memo-updater/{}", self.version))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() >= 5 || !allowed_url(attempt.url()) {
                    attempt.error("untrusted update redirect")
                } else {
                    attempt.follow()
                }
            }))
            .build()
            .map_err(|_| "UPDATE_NETWORK")
    }
    fn fetch_catalog(&self) -> Result<()> {
        if self.trust.catalog_keys.is_empty() {
            return Err("UPDATE_NOT_CONFIGURED");
        }
        let url = reqwest::Url::parse(&self.trust.feed).map_err(|_| "UPDATE_CATALOG")?;
        if !allowed_url(&url) {
            return Err("UPDATE_CATALOG");
        }
        let response = self
            .client()?
            .get(url)
            .timeout(Duration::from_secs(30))
            .send()
            .map_err(|_| "UPDATE_NETWORK")?;
        if !response.status().is_success() {
            return Err("UPDATE_NETWORK");
        }
        let mut bytes = Vec::new();
        response
            .take(MAX_FEED + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "UPDATE_NETWORK")?;
        let (catalog, digest, sequence) = decode(&bytes, &self.trust, Utc::now().timestamp())?;
        let mut s = self.state.lock().map_err(|_| "UPDATE_STORAGE")?;
        if s.error == Some("UPDATE_STORAGE") {
            return Err("UPDATE_STORAGE");
        }
        check_replay(&s.saved, sequence, &digest)?;
        let release = catalog
            .targets
            .get(&self.target)
            .filter(|r| {
                !catalog
                    .withdrawn
                    .contains(&format!("{}@{}", self.target, r.version))
            })
            .cloned();
        if let Some(r) = &release {
            validate_release(r, &self.target, &self.application_id, &self.trust)?;
        }
        let release = release.filter(|r| {
            Version::parse(&r.version).ok() > Version::parse(&self.version).ok()
                && r.android
                    .as_ref()
                    .is_none_or(|a| a.version_code > self.version_code)
        });
        let old = s.saved.clone();
        s.saved.sequence = sequence;
        s.saved.digest = digest;
        s.saved.last_checked = Utc::now().timestamp();
        if self.persist(&s.saved).is_err() {
            s.saved = old;
            return Err("UPDATE_STORAGE");
        }
        s.candidate = release;
        s.phase = if s.candidate.is_some() {
            "available"
        } else {
            "up_to_date"
        };
        s.error = None;
        drop(s);
        if let Ok(release) = self.candidate()
            && self.verify_file(&release).is_ok()
        {
            self.state.lock().map_err(|_| "UPDATE_STORAGE")?.phase = "ready";
        }
        Ok(())
    }
    fn download_inner(&self) -> Result<()> {
        let release = self.candidate()?;
        let partial = self
            .root
            .join(format!("package-{}.part", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut response = self
                .client()?
                .get(&release.url)
                .send()
                .map_err(|_| "UPDATE_NETWORK")?;
            if !response.status().is_success()
                || response.content_length().is_some_and(|n| n != release.size)
            {
                return Err("UPDATE_PACKAGE");
            }
            let mut file = private_file(&partial)?;
            copy_verified(
                &mut response,
                &mut file,
                &release,
                &self.canceled,
                |total| {
                    self.state.lock().map_err(|_| "UPDATE_STORAGE")?.downloaded = total;
                    Ok(())
                },
            )?;
            file.sync_all().map_err(|_| "UPDATE_STORAGE")?;
            drop(file);
            self.verify_desktop_signature(&partial, &release)?;
            fs::rename(&partial, self.package_path()).map_err(|_| "UPDATE_STORAGE")?;
            self.state.lock().map_err(|_| "UPDATE_STORAGE")?.phase = "ready";
            Ok(())
        })();
        let _ = fs::remove_file(partial);
        result
    }
    fn verify_file(&self, release: &Release) -> Result<()> {
        let path = self.package_path();
        let metadata = fs::symlink_metadata(&path).map_err(|_| "UPDATE_PACKAGE")?;
        if !metadata.is_file() || metadata.len() != release.size {
            return Err("UPDATE_PACKAGE");
        }
        let mut file = File::open(&path).map_err(|_| "UPDATE_PACKAGE")?;
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 65536];
        loop {
            let n = file.read(&mut buffer).map_err(|_| "UPDATE_PACKAGE")?;
            if n == 0 {
                break;
            }
            hash.update(&buffer[..n]);
        }
        if format!("{:x}", hash.finalize()) != release.sha256 {
            return Err("UPDATE_PACKAGE");
        }
        self.verify_desktop_signature(&path, release)
    }
    fn verify_desktop_signature(&self, path: &Path, release: &Release) -> Result<()> {
        verify_package_signature(path, release, &self.target, &self.trust)
    }
}

fn verify_package_signature(
    path: &Path,
    release: &Release,
    target: &str,
    trust: &Trust,
) -> Result<()> {
    if target != "windows-x86_64-nsis" {
        return Ok(());
    }
    let key = release
        .package_key_id
        .as_ref()
        .and_then(|k| trust.package_keys.get(k))
        .ok_or("UPDATE_SIGNATURE")?;
    let signature = release.signature.as_ref().ok_or("UPDATE_SIGNATURE")?;
    let decoded = BASE64_STANDARD
        .decode(signature.trim())
        .map_err(|_| "UPDATE_SIGNATURE")?;
    let text = std::str::from_utf8(&decoded).map_err(|_| "UPDATE_SIGNATURE")?;
    let sig = Signature::decode(text).map_err(|_| "UPDATE_SIGNATURE")?;
    // Tauri's version binding is checked again by restore_update/install.
    if !sig
        .trusted_comment()
        .split('\t')
        .any(|part| part == format!("version:{}", release.version))
    {
        return Err("UPDATE_SIGNATURE");
    }
    let bytes = fs::read(path).map_err(|_| "UPDATE_STORAGE")?;
    public_key(key)?
        .verify(&bytes, &sig, false)
        .map_err(|_| "UPDATE_SIGNATURE")
}

fn check_replay(saved: &Saved, sequence: u64, digest: &str) -> Result<()> {
    if sequence < saved.sequence
        || sequence == saved.sequence && !saved.digest.is_empty() && saved.digest != digest
    {
        Err("UPDATE_REPLAY")
    } else {
        Ok(())
    }
}
fn copy_verified(
    reader: &mut impl Read,
    writer: &mut impl Write,
    release: &Release,
    canceled: &AtomicBool,
    progress: impl Fn(u64) -> Result<()>,
) -> Result<()> {
    let mut buffer = [0u8; 65536];
    let mut total = 0;
    let mut hash = Sha256::new();
    loop {
        if canceled.load(Ordering::Acquire) {
            return Err("UPDATE_CANCELED");
        }
        let n = reader.read(&mut buffer).map_err(|_| "UPDATE_NETWORK")?;
        if n == 0 {
            break;
        }
        total += n as u64;
        if total > release.size || total > MAX_PACKAGE {
            return Err("UPDATE_PACKAGE");
        }
        writer
            .write_all(&buffer[..n])
            .map_err(|_| "UPDATE_STORAGE")?;
        hash.update(&buffer[..n]);
        progress(total)?;
    }
    if total != release.size || format!("{:x}", hash.finalize()) != release.sha256 {
        return Err("UPDATE_PACKAGE");
    }
    Ok(())
}

fn public_key(encoded: &str) -> Result<PublicKey> {
    let bytes = BASE64_STANDARD
        .decode(encoded.trim())
        .map_err(|_| "UPDATE_SIGNATURE")?;
    PublicKey::decode(std::str::from_utf8(&bytes).map_err(|_| "UPDATE_SIGNATURE")?)
        .map_err(|_| "UPDATE_SIGNATURE")
}
fn decode(bytes: &[u8], trust: &Trust, now: i64) -> Result<(Catalog, String, u64)> {
    if bytes.len() as u64 > MAX_FEED {
        return Err("UPDATE_CATALOG");
    }
    let env: Envelope = serde_json::from_slice(bytes).map_err(|_| "UPDATE_CATALOG")?;
    if env.envelope_version != 1 {
        return Err("UPDATE_CATALOG");
    }
    let key = trust
        .catalog_keys
        .get(&env.key_id)
        .ok_or("UPDATE_SIGNATURE")?;
    let payload = BASE64_STANDARD
        .decode(&env.payload_base64)
        .map_err(|_| "UPDATE_CATALOG")?;
    let signature = Signature::decode(&env.signature).map_err(|_| "UPDATE_SIGNATURE")?;
    public_key(key)?
        .verify(&payload, &signature, false)
        .map_err(|_| "UPDATE_SIGNATURE")?;
    let catalog: Catalog = serde_json::from_slice(&payload).map_err(|_| "UPDATE_CATALOG")?;
    let sequence = catalog
        .sequence
        .parse::<u64>()
        .map_err(|_| "UPDATE_CATALOG")?;
    if catalog.schema_version != 1
        || catalog.application_id != "app.djhx.memo"
        || catalog.channel != "stable"
        || sequence == 0
        || sequence.to_string() != catalog.sequence
    {
        return Err("UPDATE_CATALOG");
    }
    let issued = DateTime::parse_from_rfc3339(&catalog.issued_at)
        .map_err(|_| "UPDATE_CATALOG")?
        .timestamp();
    let expires = DateTime::parse_from_rfc3339(&catalog.expires_at)
        .map_err(|_| "UPDATE_CATALOG")?
        .timestamp();
    if issued > now + 300 || expires <= now || expires <= issued || expires - issued > 90 * 86400 {
        return Err("UPDATE_EXPIRED");
    }
    Ok((catalog, format!("{:x}", Sha256::digest(&payload)), sequence))
}
fn validate_release(r: &Release, target: &str, application_id: &str, trust: &Trust) -> Result<()> {
    let version = Version::parse(&r.version).map_err(|_| "UPDATE_CATALOG")?;
    let url = reqwest::Url::parse(&r.url).map_err(|_| "UPDATE_CATALOG")?;
    if !version.pre.is_empty()
        || !version.build.is_empty()
        || r.tag != format!("v{}", r.version)
        || r.commit.len() != 40
        || !r.commit.bytes().all(|b| b.is_ascii_hexdigit())
        || r.notes.len() > 32768
        || r.size == 0
        || r.size > MAX_PACKAGE
        || r.sha256.len() != 64
        || !r.sha256.bytes().all(|b| b.is_ascii_hexdigit())
        || !allowed_url(&url)
        || url.host_str() != Some("github.com")
        || !url
            .path()
            .starts_with(&format!("/Koril33/djhx-memo/releases/download/{}/", r.tag))
        || url.path_segments().is_none_or(|p| p.count() != 6)
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("UPDATE_CATALOG");
    }
    // First implementation deliberately refuses cross-protocol/schema upgrades.
    if r.protocols != [2] || r.schema_min != 3 || r.schema_max != 3 || r.min_updater != 1 {
        return Err("UPDATE_INCOMPATIBLE");
    }
    match target {
        "windows-x86_64-nsis" if r.android.is_none() && url.path().ends_with(".exe") => {
            if r.signature.is_none()
                || !r
                    .package_key_id
                    .as_ref()
                    .is_some_and(|k| trust.package_keys.contains_key(k))
            {
                return Err("UPDATE_SIGNATURE");
            }
        }
        "android-universal-apk" if url.path().ends_with(".apk") => {
            let a = r.android.as_ref().ok_or("UPDATE_CATALOG")?;
            if a.application_id != application_id
                || a.version_code == 0
                || a.min_sdk < 29
                || a.certificate_sha256.len() != 64
                || !a.certificate_sha256.bytes().all(|b| b.is_ascii_hexdigit())
                || !a.abis.contains(&"arm64-v8a".into())
                || !a.abis.contains(&"x86_64".into())
            {
                return Err("UPDATE_INCOMPATIBLE");
            }
        }
        _ => return Err("UPDATE_UNSUPPORTED"),
    }
    Ok(())
}
fn allowed_url(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && matches!(
            url.host_str(),
            Some(
                "memo.djhx.site"
                    | "github.com"
                    | "release-assets.githubusercontent.com"
                    | "objects.githubusercontent.com"
            )
        )
}
fn private_file(path: &Path) -> Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|_| "UPDATE_STORAGE")
}

fn clean_staging(root: &Path) -> Result<()> {
    for entry in fs::read_dir(root).map_err(|_| "UPDATE_STORAGE")? {
        let entry = entry.map_err(|_| "UPDATE_STORAGE")?;
        if !entry.file_type().map_err(|_| "UPDATE_STORAGE")?.is_file() {
            continue;
        }
        let name = entry.file_name();
        let name = name.to_string_lossy();
        let id = name
            .strip_prefix("package-")
            .and_then(|n| n.strip_suffix(".part"))
            .or_else(|| {
                name.strip_prefix("state-")
                    .and_then(|n| n.strip_suffix(".tmp"))
            });
        if id.is_some_and(|id| uuid::Uuid::parse_str(id).is_ok()) {
            fs::remove_file(entry.path()).map_err(|_| "UPDATE_STORAGE")?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests;
