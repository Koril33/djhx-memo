#!/usr/bin/env python3
"""Build the update-catalog manifest for CI. Does not sign, upload or deploy.

Reads version/versionCode/minSdk/ABIs from android/app/build.gradle.kts, key ids
from config/update-trust.json, and derives the next catalog sequence from the
deployed current.json (strictly higher, per update-publishing.md). The Android
certificate SHA-256 is the stable release keystore certificate (unchanged since
the original APK; recorded in docs/releases).
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import urllib.request

# Stable release certificate SHA-256 (JKS), lowercase hex. See docs/releases/0.1.10-validation.md.
ANDROID_CERTIFICATE_SHA256 = "ec8a88aa7aad47d51fa07ffddb4a17bb69c4aad71ee3c0dd4b3ad0eb4f5cdcdb"
# application_id is fixed by android/app/build.gradle.kts.
APPLICATION_ID = "app.djhx.memo"


def read_gradle(root: Path):
    text = (root / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version_code = re.search(r'versionCode\s*=.*?else\s+(\d+)', text)
    min_sdk = re.search(r'minSdk\s*=\s*(\d+)', text)
    abis = re.search(r'abiFilters\s*\+=\s*listOf\(([^)]*)\)', text)
    if not (version_code and min_sdk and abis):
        raise SystemExit("Could not parse versionCode/minSdk/abis from android/app/build.gradle.kts")
    return int(version_code.group(1)), int(min_sdk.group(1)), re.findall(r'"([^"]+)"', abis.group(1))


def next_sequence(root: Path):
    override = os.environ.get("DJHX_CATALOG_SEQUENCE")
    if override:
        return int(override)
    feed = json.loads((root / "config/update-trust.json").read_text(encoding="utf-8-sig"))["feed"]
    with urllib.request.urlopen(feed, timeout=30) as response:
        envelope = json.loads(response.read().decode("utf-8"))
    payload = json.loads(base64.b64decode(envelope["payload_base64"]))
    return int(payload["sequence"]) + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="release tag, e.g. v0.1.11")
    parser.add_argument("--commit", required=True, help="40-char reviewed source commit")
    parser.add_argument("--assets-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    version = args.tag.removeprefix("v")
    version_code, min_sdk, abis = read_gradle(root)
    trust = json.loads((root / "config/update-trust.json").read_text(encoding="utf-8-sig"))
    catalog_key_id = next(iter(trust["catalog_keys"]))
    package_key_id = next(iter(trust["package_keys"]))
    assets = args.assets_dir
    exe = assets / f"djhx-memo-{version}-windows-x64-setup.exe"
    sig = assets / f"djhx-memo-{version}-windows-x64-setup.exe.sig"
    apk = assets / f"djhx-memo-{version}-android.apk"
    for path in (exe, sig, apk):
        if not path.is_file():
            raise SystemExit(f"missing release asset: {path}")
    base = f"https://github.com/Koril33/djhx-memo/releases/download/{args.tag}"
    manifest = {
        "sequence": str(next_sequence(root)),
        "catalog_key_id": catalog_key_id,
        "targets": {
            "windows-x86_64-nsis": {
                "version": version,
                "commit": args.commit,
                "notes": "Windows / Android 更新客户端",
                "file": str(exe.resolve()),
                "url": f"{base}/djhx-memo-{version}-windows-x64-setup.exe",
                "signature_file": str(sig.resolve()),
                "package_key_id": package_key_id,
            },
            "android-universal-apk": {
                "version": version,
                "commit": args.commit,
                "notes": "Windows / Android 更新客户端",
                "file": str(apk.resolve()),
                "url": f"{base}/djhx-memo-{version}-android.apk",
                "android": {
                    "application_id": APPLICATION_ID,
                    "version_code": version_code,
                    "min_sdk": min_sdk,
                    "certificate_sha256": ANDROID_CERTIFICATE_SHA256,
                    "abis": abis,
                },
            },
        },
        "withdrawn": [],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Catalog manifest written: {args.output} (sequence {manifest['sequence']})")


if __name__ == "__main__":
    main()
