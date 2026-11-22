#!/usr/bin/env python3
"""Release-package safety regression; synthetic bytes, no network or credentials."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('mirror', Path(__file__).with_name('prepare-download-assets.py'))
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)
publish_spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-download-assets.py'))
publisher = importlib.util.module_from_spec(publish_spec)
publish_spec.loader.exec_module(publisher)


class MirrorTests(unittest.TestCase):
    def test_immutable_bundle_and_alias_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / 'assets'
            assets.mkdir()
            files = mirror.names('v0.2.2')
            for name in files:
                (assets / name).write_bytes(name.encode())
            alias = 'djhx-memo-linux-amd64.deb'
            (assets / alias).write_bytes((assets / files[-1]).read_bytes())
            (assets / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256((assets / n).read_bytes()).hexdigest()}  {n}\n' for n in files + [alias]))
            mirror.stage('v0.2.2', assets, root / 'good')
            self.assertEqual(mirror.verify(root / 'good')['tag'], 'v0.2.2')
            publisher.publish(root / 'good', root / 'public')
            publisher.publish(root / 'good', root / 'public')
            target = root / 'public/releases/v0.2.2' / files[0]
            target.write_bytes(b'other existing release file')
            with self.assertRaises(ValueError): publisher.publish(root / 'good', root / 'public')
            self.assertEqual(target.read_bytes(), b'other existing release file')
            (root / 'good/releases/v0.2.2' / files[0]).write_bytes(b'corrupted')
            with self.assertRaises(ValueError): mirror.verify(root / 'good')
            (assets / alias).write_bytes(b'wrong architecture/version')
            with self.assertRaises(ValueError): mirror.stage('v0.2.2', assets, root / 'bad')
            self.assertFalse((root / 'bad').exists())

    def test_tag_cannot_escape_or_select_prerelease(self):
        for value in ['../v0.2.2', 'v0.2.2/evil', 'v0.2.2-beta', 'v0.2.2?source=other']:
            with self.assertRaises(ValueError): mirror.names(value)

    def test_deployment_archive_rejects_escape_and_link_members(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, link in [('site/index.html', False), ('../escape', False), ('site/link', True)]:
                archive = root / 'bundle.tar.gz'
                with tarfile.open(archive, 'w:gz') as out:
                    item = tarfile.TarInfo(name)
                    if link:
                        item.type = tarfile.SYMTYPE
                        item.linkname = '/outside'
                        out.addfile(item)
                    else:
                        item.size = 4
                        out.addfile(item, io.BytesIO(b'test'))
                if name == 'site/index.html':
                    mirror.extract(archive, root / 'ok')
                    self.assertEqual((root / 'ok/site/index.html').read_bytes(), b'test')
                else:
                    with self.assertRaises(ValueError): mirror.extract(archive, root / 'bad')


if __name__ == '__main__': unittest.main()
