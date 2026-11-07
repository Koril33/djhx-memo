#!/usr/bin/env python3
"""Isolated tests for the actual snapshot/encryption/retention script."""
import datetime as dt
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else (
    ROOT / "dist/server/djhx-memo-server-0.1.0-linux-amd64/memo-server-linux-amd64"
)


def run(*args, success=True):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if success and result.returncode != 0:
        raise RuntimeError(f"Command failed: {args[0]} (exit {result.returncode})")
    if not success and result.returncode == 0:
        raise RuntimeError("Corrupted input or invalid recipient was accepted")
    return result


with tempfile.TemporaryDirectory(prefix="djhx-backup-smoke-") as temporary:
    base = Path(temporary)
    os.chmod(base, 0o700)
    private, public = base / "private.pem", base / "recipient.pem"
    run("openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "1",
        "-subj", "/CN=djhx-backup-isolated-test", "-keyout", private, "-out", public)
    os.chmod(private, 0o600)
    db, epoch = base / "db/memo.db", base / "runtime/epoch"
    # The one-use fixture invitation is captured and discarded, never printed.
    run(BINARY, "invite-create", "--db", db, "--epoch-file", epoch)
    snapshots, exported = base / "snapshots", base / "exported"
    snapshots.mkdir(mode=0o700)
    exported.mkdir(mode=0o750)
    os.chmod(exported, 0o2750)
    command = [ROOT / "server/deploy/backup-cloud.sh", "--server-bin", BINARY,
               "--db", db, "--epoch-file", epoch, "--snapshot-dir", snapshots,
               "--export-dir", exported, "--recipient", public]
    run(*command)
    today = dt.datetime.now(dt.timezone.utc).date()
    monday = today - dt.timedelta(days=today.weekday())
    daily = exported / f"memo-daily-{today}.cms"
    weekly = exported / f"memo-weekly-{monday}.cms"
    restored = base / "isolated-restore/memo.db"
    restored.parent.mkdir(mode=0o700)
    run("openssl", "cms", "-decrypt", "-binary", "-inform", "DER", "-in", daily,
        "-recip", public, "-inkey", private, "-out", restored)
    run(BINARY, "check", "--db", restored, "--epoch-file", base / "unused-epoch")
    damaged = base / "damaged.cms"
    ciphertext = bytearray(daily.read_bytes())
    ciphertext[-1] ^= 1
    damaged.write_bytes(ciphertext)
    run("openssl", "cms", "-decrypt", "-binary", "-inform", "DER", "-in", damaged,
        "-recip", public, "-inkey", private, "-out", base / "rejected.partial", success=False)

    first_weekly_hash = hashlib.sha256(weekly.read_bytes()).digest()
    for days in range(1, 9):
        shutil.copyfile(daily, exported / f"memo-daily-{today - dt.timedelta(days=days)}.cms")
    for weeks in range(1, 5):
        shutil.copyfile(daily, exported / f"memo-weekly-{monday - dt.timedelta(weeks=weeks)}.cms")
    untouched = exported / "operator-keep.txt"
    untouched.write_text("unrelated file\n")
    malformed = exported / "memo-daily-2000-02-30.cms"
    malformed.write_text("invalid date must not be deleted\n")
    run(*command)
    assert weekly.read_bytes() and hashlib.sha256(weekly.read_bytes()).digest() == first_weekly_hash
    expected_daily = {f"memo-daily-{today - dt.timedelta(days=d)}.cms" for d in range(7)}
    actual_daily = {path.name for path in exported.glob("memo-daily-*.cms")} - {malformed.name}
    expected_weekly = {f"memo-weekly-{monday - dt.timedelta(weeks=w)}.cms" for w in range(4)}
    assert actual_daily == expected_daily
    assert {path.name for path in exported.glob("memo-weekly-*.cms")} == expected_weekly
    assert untouched.exists() and malformed.exists()
    assert daily.stat().st_mode & 0o777 == 0o640
    assert weekly.stat().st_mode & 0o777 == 0o640

    previous = hashlib.sha256(daily.read_bytes()).digest()
    invalid_recipient = base / "not-a-certificate.pem"
    invalid_recipient.write_text("invalid certificate\n")
    failed = command[:-1] + [invalid_recipient]
    run(*failed, success=False)
    assert hashlib.sha256(daily.read_bytes()).digest() == previous
    assert not list(exported.glob(".memo-*"))
    assert not list(snapshots.glob(".job.*"))

    # Long outages must not delete the last useful historical snapshots.
    for path in exported.glob("memo-*.cms"):
        if path != malformed:
            path.unlink()
    old_daily = {"memo-daily-2000-01-01.cms", "memo-daily-2000-01-02.cms"}
    old_weekly = {"memo-weekly-2000-01-03.cms", "memo-weekly-2000-01-10.cms"}
    for name in old_daily | old_weekly:
        (exported / name).write_bytes(ciphertext)
    run(*command)
    assert all((exported / name).exists() for name in old_daily | old_weekly)

    # Exercise the real rsync process over a local transport fixture. Production
    # SSH identity/host-key handling is checked separately on the deployed host.
    remote_fixture = base / "remote-fixture"
    remote_fixture.mkdir(mode=0o700)
    source = remote_fixture / daily.name
    shutil.copy2(daily, source)
    (remote_fixture / "not-a-backup.txt").write_text("must not be received\n")
    (remote_fixture / ".partial").mkdir()
    (remote_fixture / ".partial" / daily.name).write_text("incomplete\n")
    tools = base / "fixture-tools"
    tools.mkdir()
    ssh = tools / "ssh"
    ssh.write_text("#!/usr/bin/env python3\nimport os, sys\n"
                   "args = sys.argv[sys.argv.index('/usr/bin/rsync'):]\n"
                   "os.execv('/usr/bin/rsync', args)\n")
    ssh.chmod(0o700)
    notify = tools / "notify-send"
    notify.write_text("#!/bin/sh\nprintf 'notified\\n' >> \"$TEST_NOTIFY_FILE\"\n")
    notify.chmod(0o700)
    local_state, destination = base / "pull-state", base / "local-copies"
    keys = local_state / "keys"
    keys.mkdir(parents=True, mode=0o700)
    shutil.copy2(private, keys / "private.pem")
    shutil.copy2(public, keys / "recipient.pem")
    notify_log = base / "notifications"
    environment = os.environ | {
        "PATH": str(tools) + os.pathsep + os.environ["PATH"],
        "DJHX_BACKUP_HOST": "fixture@fixture.invalid",
        "DJHX_BACKUP_REMOTE_DIR": str(remote_fixture) + "/",
        "DJHX_BACKUP_DEST": str(destination),
        "DJHX_BACKUP_STATE": str(local_state),
        "TEST_NOTIFY_FILE": str(notify_log),
    }

    def pull(success=True):
        result = subprocess.run([str(ROOT / "scripts/backup-pull.sh")],
                                env=environment, capture_output=True, text=True)
        if (result.returncode == 0) != success:
            raise RuntimeError("Pull fixture produced an unexpected exit status: " + result.stderr)
        assert not list(destination.glob(".incoming.*"))

    pull()
    received = destination / daily.name
    assert received.exists() and received.stat().st_mode & 0o777 == 0o600
    assert not (destination / "not-a-backup.txt").exists()
    assert not (destination / ".partial").exists()
    assert "status=ok\n" in (local_state / "pull.status").read_text()
    prior_good = received.read_bytes()
    # Same size/mtime, changed authentication tag: --checksum must fetch it, and
    # staging validation must reject it before replacing the prior good copy.
    original_times = source.stat()
    changed = bytearray(source.read_bytes())
    changed[-1] ^= 1
    source.write_bytes(changed)
    os.utime(source, ns=(original_times.st_atime_ns, original_times.st_mtime_ns))
    pull(success=False)
    pull(success=False)
    assert received.read_bytes() == prior_good
    assert "status=failed\n" in (local_state / "pull.status").read_text()
    assert notify_log.read_text().count("notified") == 1
    source.write_bytes(prior_good)
    pull()
    stale = dt.datetime.now(dt.timezone.utc).timestamp() - 27 * 3600
    os.utime(source, (stale, stale))
    local_mtime = received.stat().st_mtime_ns
    first_stale = remote_fixture / "memo-daily-1999-12-31.cms"
    first_stale.write_bytes(prior_good)
    os.utime(first_stale, (stale, stale))
    prior_status = (local_state / "pull.status").read_text()
    prior_success = next(line for line in prior_status.splitlines() if line.startswith("last_success_at="))
    pull(success=False)
    assert received.read_bytes() == prior_good
    assert received.stat().st_mtime_ns == local_mtime
    assert (destination / first_stale.name).read_bytes() == prior_good
    assert prior_success in (local_state / "pull.status").read_text()
    assert notify_log.read_text().count("notified") == 2
    environment["DJHX_BACKUP_DEST"] = str(base / "initial-stale-copies")
    pull(success=False)
    assert (Path(environment["DJHX_BACKUP_DEST"]) / source.name).read_bytes() == prior_good

    # A real rsync source failure must not replace or remove independent copies.
    environment["DJHX_BACKUP_DEST"] = str(destination)
    environment["DJHX_BACKUP_REMOTE_DIR"] = str(base / "missing-remote-directory") + "/"
    previous_files = {path.name: path.read_bytes() for path in destination.glob("*.cms")}
    pull(success=False)
    assert {path.name: path.read_bytes() for path in destination.glob("*.cms")} == previous_files
    assert prior_success in (local_state / "pull.status").read_text()

print("PASS: snapshot/check, authenticated decrypt, corruption rejection, 7-daily/4-weekly retention, outage preservation, first-week preservation, failure atomicity and permissions")
print("PASS: real-rsync staging/filtering, source failure, checksum detection, authenticated promotion, previous-copy preservation, 26-hour expiry and deduplicated failure notifications (local transport fixture)")
