#!/usr/bin/env python3
"""Build a signed static catalog locally. Does not upload, deploy or replace release assets."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(command):
    result = subprocess.run(command, capture_output=True)
    if result.returncode:
        raise ValueError("Signing/validation command failed; private diagnostics were not printed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    trust = json.loads((root / "config/update-trust.json").read_text(encoding="utf-8-sig"))
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    now = datetime.now(timezone.utc).replace(microsecond=0)
    payload = {"schema_version": 1, "application_id": "app.djhx.memo", "channel": "stable",
               "sequence": str(manifest["sequence"]), "issued_at": now.isoformat(),
               "expires_at": (now + timedelta(days=30)).isoformat(), "targets": {},
               "withdrawn": manifest.get("withdrawn", [])}
    assets = []
    for target, item in manifest["targets"].items():
        artifact = Path(item["file"])
        assets.extend(["--asset", target, str(artifact.resolve())])
        with artifact.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        payload["targets"][target] = {
            "version": item["version"], "tag": "v" + item["version"], "commit": item["commit"],
            "notes": item.get("notes", ""), "url": item["url"], "size": artifact.stat().st_size,
            "sha256": digest, "signature": Path(item["signature_file"]).read_text().strip() if item.get("signature_file") else None,
            "package_key_id": item.get("package_key_id"), "protocols": [2], "schema_min": 3, "schema_max": 3,
            "min_updater": 1, "android": item.get("android"),
        }
    if not os.environ.get("TAURI_SIGNING_PRIVATE_KEY"):
        raise ValueError("Load the catalog private key in the signing process environment first")
    args.output.mkdir(parents=True, exist_ok=True)
    current = args.output / "current.json"
    if current.exists():
        previous = json.loads(base64.b64decode(json.loads(current.read_text())["payload_base64"]))
        if int(payload["sequence"]) <= int(previous["sequence"]):
            raise ValueError("Use a strictly higher catalog sequence")
    with tempfile.TemporaryDirectory(prefix="memo-catalog-") as temporary:
        path = Path(temporary) / "payload.json"
        path.write_bytes(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode())
        cli = root / "desktop/node_modules/@tauri-apps/cli/tauri.js"
        run(["node", str(cli), "signer", "sign", str(path)])
        envelope = {"envelope_version": 1, "key_id": manifest.get("catalog_key_id", next(iter(trust["catalog_keys"]))),
                    "payload_base64": base64.b64encode(path.read_bytes()).decode(),
                    "signature": base64.b64decode(path.with_suffix(".json.sig").read_text().strip()).decode()}
        checked = Path(temporary) / "envelope.json"
        checked.write_text(json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        run(["cargo", "run", "--offline", "--locked", "--release", "--manifest-path", str(root / "core/Cargo.toml"),
             "--bin", "validate-update-catalog", "--", str(checked)] + assets)
        versioned = args.output / ("catalog-" + payload["sequence"] + ".json")
        with versioned.open("xb") as out:
            out.write(checked.read_bytes())
            out.flush()
            os.fsync(out.fileno())
        pending = args.output / ".current.tmp"
        with pending.open("xb") as out:
            out.write(checked.read_bytes())
            out.flush()
            os.fsync(out.fileno())
        os.replace(pending, args.output / "current.json")
    print("Verified signed catalog created locally. No deployment or upload performed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, StopIteration):
        raise SystemExit("Catalog preparation failed. Check the manifest, signing environment and existing output sequence.")
