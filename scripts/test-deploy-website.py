#!/usr/bin/env python3
"""Local deployment preflight regression; no network, SSH or sudo is executed."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import tarfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASH = os.environ.get('DJHX_TEST_BASH') or shutil.which('bash')
mirror_spec = importlib.util.spec_from_file_location('mirror_fixture', ROOT / 'scripts/prepare-download-assets.py')
mirror = importlib.util.module_from_spec(mirror_spec)
mirror_spec.loader.exec_module(mirror)


def shell_path(path):
    value = Path(path).resolve().as_posix()
    return '/' + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ':' else value


class DeployPreflightTests(unittest.TestCase):
    def run_fixture(self, unavailable=False, explicit=False, upload_mode=None):
        self.assertTrue(BASH, 'Bash is required; on Windows set DJHX_TEST_BASH to Git Bash')
        temporary_root = ROOT / '.local'
        temporary_root.mkdir(exist_ok=True)
        self.assertTrue(temporary_root.resolve().is_relative_to(ROOT.resolve()))
        with tempfile.TemporaryDirectory(prefix='deploy-preflight-', dir=temporary_root) as directory:
            fixture = Path(directory)
            binary = fixture / 'bin'
            binary.mkdir()
            (fixture / 'tmp').mkdir()

            def command(name, body):
                path = binary / name
                with path.open('w', encoding='utf-8', newline='\n') as stream:
                    stream.write('#!/usr/bin/env bash\n' + body + '\n')
                path.chmod(0o755)

            command('python3', 'exit 49')  # Executable WindowsApps stub, no output.
            command('py', 'exit 49')
            command('python', 'exit 49' if unavailable else 'exec ' + shlex.quote(shell_path(sys.executable)) + ' "$@"')
            command('custom python', 'exec ' + shlex.quote(shell_path(sys.executable)) + ' "$@"')
            command('gh', 'printf called > "$FIXTURE_ROOT/download-called"\nexit 42')
            for name in ['ssh', 'scp', 'cargo']:
                command(name, 'printf unsafe > "$FIXTURE_ROOT/remote-called"\nexit 88')
            env = dict(os.environ, FIXTURE_ROOT=shell_path(fixture), TMPDIR=shell_path(fixture / 'tmp'),
                       DJHX_DEPLOY_SSH_TARGET='fixture@example.invalid')
            env.pop('DJHX_DEPLOY_PYTHON', None)
            test_secret = hashlib.sha256(b'orchestration-only fixture input').hexdigest()[:20]
            if upload_mode:
                self.prepare_upload_fixture(fixture, command)
                env['FIXTURE_UPLOAD_MODE'] = upload_mode
            if explicit:
                env['DJHX_DEPLOY_PYTHON'] = shell_path(binary / 'custom python')
            result = subprocess.run(
                [BASH, '--noprofile', '--norc', '-c',
                 'export PATH="$1:/usr/bin:/bin"; shift; exec bash "$@"', 'deploy-test',
                 shell_path(binary), shell_path(ROOT / 'scripts/deploy-website.sh'), 'v0.2.2'],
                env=env, input=('yes\n' + test_secret + '\n').encode() if upload_mode else None,
                capture_output=True, timeout=30,
            )
            result.stdout = result.stdout.decode('utf-8', errors='replace')
            result.stderr = result.stderr.decode('utf-8', errors='replace')
            self.assertFalse((fixture / 'remote-called').exists())
            self.assertEqual(list((fixture / 'tmp').iterdir()), [])
            if upload_mode:
                self.assertNotIn(test_secret, result.stdout + result.stderr)
                self.assertTrue((fixture / 'scp-count').exists(), result.stdout + result.stderr)
                count = int((fixture / 'scp-count').read_text())
                invoked = (fixture / 'sudo-stdin').exists()
                if invoked:
                    self.assertEqual((fixture / 'sudo-stdin').read_text(), test_secret + '\n')
                    self.assertEqual((fixture / 'sudo-count').read_text().strip(), '1')
                options = (fixture / 'ssh-options').read_text()
                self.assertIn('StrictHostKeyChecking=yes', options)
                self.assertIn('ServerAliveInterval=15', options)
                return result, count, invoked
            return result, (fixture / 'download-called').exists()

    def prepare_upload_fixture(self, fixture, command):
        # Real extraction/hash checks run on synthetic bytes; cargo/SSH are isolated
        # substitutes so these tests only claim orchestration and receipt behavior.
        assets = fixture / 'assets'
        assets.mkdir()
        names = mirror.names('v0.2.2')
        for name in names:
            (assets / name).write_bytes(name.encode())
        alias = 'djhx-memo-linux-amd64.deb'
        (assets / alias).write_bytes((assets / names[-1]).read_bytes())
        (assets / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256((assets / name).read_bytes()).hexdigest()}  {name}\n'
            for name in names + [alias]), encoding='utf-8')
        bundle = fixture / 'bundle'
        mirror.stage('v0.2.2', assets, bundle / 'downloads')
        (bundle / 'site').mkdir()
        (bundle / 'site/index.html').write_text('synthetic site', encoding='utf-8')
        (bundle / 'updates/stable').mkdir(parents=True)
        envelope = {'payload_base64': base64.b64encode(json.dumps({'sequence': '13'}).encode()).decode()}
        (bundle / 'updates/stable/current.json').write_text(json.dumps(envelope), encoding='utf-8')
        with tarfile.open(fixture / 'bundle.tar.gz', 'w:gz') as archive:
            for name in ['site', 'updates', 'downloads']:
                archive.add(bundle / name, arcname=name)
        command('gh', '''while [[ "$#" -gt 0 ]]; do
 if [[ "$1" == --dir ]]; then destination="$2"; break; fi
 shift
done
cp "$FIXTURE_ROOT/bundle.tar.gz" "$destination/deploy-bundle-0.2.2.tar.gz"''')
        command('cargo', 'exit 0')
        command('scp', '''count=0
if [[ -f "$FIXTURE_ROOT/scp-count" ]]; then count=$(cat "$FIXTURE_ROOT/scp-count"); fi
count=$((count+1)); printf '%s' "$count" > "$FIXTURE_ROOT/scp-count"
if [[ "$FIXTURE_UPLOAD_MODE" == exhausted || ( "$FIXTURE_UPLOAD_MODE" == flaky && "$count" -lt 3 ) ]]; then
 echo 'lost connection' >&2; exit 1
fi''')
        command('ssh', '''printf '%s\\n' "$@" >> "$FIXTURE_ROOT/ssh-options"
if [[ "$*" == *'sudo -S'* ]]; then
 count=0
 if [[ -f "$FIXTURE_ROOT/sudo-count" ]]; then count=$(cat "$FIXTURE_ROOT/sudo-count"); fi
 printf '%s' "$((count+1))" > "$FIXTURE_ROOT/sudo-count"
 cat > "$FIXTURE_ROOT/sudo-stdin"
 if [[ "$FIXTURE_UPLOAD_MODE" == remote-failure ]]; then exit 55; fi
fi''')

    def test_python_alias_falls_back_and_download_failure_names_its_stage(self):
        result, downloaded = self.run_fixture()
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertTrue(downloaded)
        self.assertIn('==> local Python:', result.stdout)
        self.assertIn('download bundle', result.stderr)
        self.assertIn('exit 42', result.stderr)
        self.assertNotIn('sudo password', result.stdout + result.stderr)

    def test_no_usable_python_stops_before_any_download_or_remote_work(self):
        result, downloaded = self.run_fixture(unavailable=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(downloaded)
        self.assertIn('Working Python 3.9+ required', result.stderr)

    def test_explicit_executable_path_with_spaces_is_not_split(self):
        result, downloaded = self.run_fixture(unavailable=True, explicit=True)
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertTrue(downloaded)
        self.assertIn('download bundle', result.stderr)

    def test_interrupted_upload_retries_and_keeps_sudo_input_private(self):
        result, uploads, promoted = self.run_fixture(upload_mode='flaky')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(uploads, 5)
        self.assertTrue(promoted)
        self.assertIn('Deploy complete', result.stdout)

    def test_exhausted_upload_never_promotes(self):
        result, uploads, promoted = self.run_fixture(upload_mode='exhausted')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(uploads, 3)
        self.assertFalse(promoted)
        self.assertIn('upload staging', result.stderr)

    def test_remote_promotion_error_is_not_automatically_replayed(self):
        result, uploads, promoted = self.run_fixture(upload_mode='remote-failure')
        self.assertEqual(result.returncode, 55, result.stderr)
        self.assertEqual(uploads, 3)
        self.assertTrue(promoted)
        self.assertIn('remote deployment', result.stderr)


if __name__ == '__main__':
    unittest.main()
