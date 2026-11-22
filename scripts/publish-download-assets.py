#!/usr/bin/env python3
"""Publish checked mirror files without overwriting immutable versioned assets."""
import argparse
import importlib.util
import os
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location('mirror', Path(__file__).with_name('prepare-download-assets.py'))
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


def publish(source, destination):
    manifest = mirror.verify(source)
    destination.mkdir(parents=True, exist_ok=True)
    base = destination.resolve()
    release = destination / 'releases' / manifest['tag']
    release.mkdir(parents=True, exist_ok=True)
    if release.is_symlink() or not release.resolve().is_relative_to(base):
        raise ValueError('Unsafe destination directory')
    for name, item in manifest['files'].items():
        target = release / name
        if target.exists():
            if mirror.digest(target) != item['sha256']:
                raise ValueError('Refusing to replace an immutable release file')
            continue
        fd, temporary = tempfile.mkstemp(prefix='.publishing-', dir=release)
        try:
            with os.fdopen(fd, 'wb') as out, (source / 'releases' / manifest['tag'] / name).open('rb') as data:
                while chunk := data.read(65536): out.write(chunk)
                out.flush()
                os.fsync(out.fileno())
            os.chmod(temporary, 0o644)
            if mirror.digest(Path(temporary)) != item['sha256']:
                raise ValueError('Staged file changed during publication')
            os.link(temporary, target)
        finally:
            os.unlink(temporary)
    print(f"Published immutable mirror files for {manifest['tag']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    publish(args.source, args.destination)
