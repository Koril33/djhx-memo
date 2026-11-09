#!/usr/bin/env python3
"""Scan public source, reachable Git history and commit messages using Gitleaks.

Only repository source is exported; ignored runtime data, signing keys and build
caches are deliberately outside the publishing boundary. Reports are redacted.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> int:
    """Run all scans, returning 1 for findings and 2 for an execution failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gitleaks", default="gitleaks", help="Gitleaks executable (validated: 8.30.1)")
    args = parser.parse_args()
    executable = shutil.which(args.gitleaks)
    if not executable:
        parser.error("Gitleaks is missing; install the pinned tool described in SECURITY.md")
    executable = str(Path(executable).resolve())
    root = Path(__file__).resolve().parent.parent
    reports = root / ".local" / "secret-scan"
    reports.mkdir(parents=True, exist_ok=True)
    config = root / ".gitleaks.toml"
    version = subprocess.check_output([executable, "version"], text=True).strip()
    names = subprocess.check_output(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=root
    ).decode().split("\0")
    status = 0

    def scan(label: str, command: list[str], input_data: bytes | None = None) -> None:
        nonlocal status
        report = reports / (label + ".json")
        report.unlink(missing_ok=True)
        result = subprocess.run(
            [executable, *command, "--config", str(config), "--redact=100",
             "--report-format=json", "--report-path", str(report)],
            cwd=root, input=input_data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        (reports / (label + ".log")).write_bytes(result.stdout)
        if result.returncode not in (0, 1) or not report.is_file():
            print(f"{label}: scanner failed; inspect redacted local log")
            status = 2
            return
        findings = json.loads(report.read_text(encoding="utf-8"))
        print(f"{label}: {len(findings)} findings")
        if result.returncode == 1 or findings:
            status = max(status, 1)

    with tempfile.TemporaryDirectory(prefix="source-", dir=reports) as temp:
        snapshot = Path(temp)
        count = 0
        for name in sorted(set(names)):
            if not name:
                continue
            source = root / name
            if source.is_symlink():
                parser.error(f"Refusing to follow a source symlink: {name}")
            if not source.is_file():
                continue
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            count += 1
        print(f"Gitleaks {version}; {count} public source files; reports: .local/secret-scan/")
        scan("history", ["git", "--log-opts=--all", str(root)])
        scan("worktree", ["dir", str(snapshot)])
        metadata = subprocess.check_output(["git", "log", "--all", "--format=fuller"], cwd=root)
        scan("commit-metadata", ["stdin"], metadata)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
