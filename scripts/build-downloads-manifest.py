#!/usr/bin/env python3
"""Generate website/downloads.json from the release tag. Three platforms share
one version in v1 (a single-platform fork, like the historical 0.1.9 Android
patch, is not supported by this generator and must be hand-written then)."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="release tag, e.g. v0.1.11")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    version = args.tag.removeprefix("v")
    manifest = {
        "version": version,
        "platforms": {
            "windows": {"version": version, "asset": f"djhx-memo-{version}-windows-x64-setup.exe"},
            "android": {"version": version, "asset": f"djhx-memo-{version}-android.apk"},
            "linux": {"version": version, "asset": f"djhx-memo-{version}-linux-amd64.deb"},
        },
    }
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"downloads.json written: {args.output} (version {version})")


if __name__ == "__main__":
    main()
