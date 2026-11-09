#!/usr/bin/env python3
"""Collect upstream notices from the exact locally resolved Cargo packages."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("manifest", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
metadata = json.loads(subprocess.check_output([
    "cargo", "metadata", "--manifest-path", str(args.manifest),
    "--locked", "--offline", "--format-version", "1",
]))
parts = [
    "djhx-memo — 第三方 Rust 依赖与许可证\n"
    "依据提交的 Cargo.lock 和本机已校验的 crate 源码生成；包含跨平台可选依赖。\n"
    "SPDX 的 OR 表示可选择其中许可。MPL-2.0 依赖源码可从所列上游与 crates.io 获取，"
    "本项目未修改 UniFFI 上游源码。随包 SQLite 为公有领域；其捆绑源文件亦保留原始声明。\n"
]
texts = {}
for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
    if package["source"] is None:
        continue
    directory = Path(package["manifest_path"]).parent
    notices = set()
    for pattern in ("LICENSE*", "LICENCE*", "COPYING*", "NOTICE*", "license*", "licence*"):
        notices.update(path for path in directory.glob(pattern) if path.is_file())
    if package["name"] == "onig_sys":
        notices.add(directory / "oniguruma/COPYING")
    if package.get("license_file"):
        notices.add(directory / package["license_file"])
    parts.append("\n" + "=" * 72 + "\n" + package["name"] + " " + package["version"] +
                 "\nSPDX: " + (package.get("license") or "see upstream license file") +
                 "\nSource: https://crates.io/crates/" + package["name"] + "/" + package["version"] +
                 "\nUpstream: " + (package.get("repository") or package.get("homepage") or "crates.io") + "\n")
    for path in sorted(notices):
        content = "\n".join(line.rstrip() for line in path.read_text(encoding="utf-8", errors="replace").splitlines()).rstrip() + "\n"
        identifier = hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]
        texts[identifier] = content
        parts.append("License file: " + path.name + " — full text " + identifier + "\n")
for identifier, content in sorted(texts.items()):
    parts.append("\n" + "=" * 72 + "\nLicense text " + identifier + "\n\n" + content + "\n")
args.output.write_text("".join(parts).rstrip() + "\n", encoding="utf-8")
