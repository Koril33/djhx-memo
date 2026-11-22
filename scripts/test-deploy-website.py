#!/usr/bin/env python3
"""Local deployment preflight regression; no network, SSH or sudo is executed."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASH = os.environ.get('DJHX_TEST_BASH') or shutil.which('bash')


def shell_path(path):
    value = Path(path).resolve().as_posix()
    return '/' + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ':' else value


class DeployPreflightTests(unittest.TestCase):
    def run_fixture(self, unavailable=False, explicit=False):
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
            if explicit:
                env['DJHX_DEPLOY_PYTHON'] = shell_path(binary / 'custom python')
            result = subprocess.run(
                [BASH, '--noprofile', '--norc', '-c',
                 'export PATH="$1:/usr/bin:/bin"; shift; exec bash "$@"', 'deploy-test',
                 shell_path(binary), shell_path(ROOT / 'scripts/deploy-website.sh'), 'v0.2.2'],
                env=env, capture_output=True, text=True, timeout=30,
            )
            self.assertFalse((fixture / 'remote-called').exists())
            self.assertEqual(list((fixture / 'tmp').iterdir()), [])
            return result, (fixture / 'download-called').exists()

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


if __name__ == '__main__':
    unittest.main()
