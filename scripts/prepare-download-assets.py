#!/usr/bin/env python3
"""Stage immutable client files for the personal mirror, or verify that bundle.

Uses the authenticated Release's SHA256SUMS. Catalog signatures are additionally
verified by the existing Rust validator before deployment; this script never
signs, uploads, rewrites an artifact, or accepts arbitrary destination paths.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile


def extract(archive, destination):
    with tarfile.open(archive, 'r:gz') as stream:
        members = []
        total = 0
        for member in stream:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or '..' in path.parts or not path.parts
                    or '\\' in member.name or ':' in member.name
                    or path.parts[0] not in {'site', 'updates', 'downloads'}
                    or not (member.isfile() or member.isdir())):
                raise ValueError('Unsafe deployment archive member')
            total += member.size
            members.append(member)
            if total > 3 * 1024 ** 3 or len(members) > 10000:
                raise ValueError('Deployment archive is too large')
        base = destination.resolve()
        base.mkdir(parents=True, exist_ok=True)
        for member in members:
            target = base.joinpath(*PurePosixPath(member.name).parts)
            if target.is_symlink() or not target.resolve().is_relative_to(base):
                raise ValueError('Unsafe extraction destination')
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as out, stream.extractfile(member) as source:
                    shutil.copyfileobj(source, out)


def names(tag):
    if not re.fullmatch(r"v0\.\d+\.\d+", tag):
        raise ValueError("Expected a stable client tag")
    version = tag[1:]
    return [f"djhx-memo-{version}-{suffix}" for suffix in
            ("windows-x64-setup.exe", "windows-x64-setup.exe.sig", "android.apk", "linux-amd64.deb")]


def digest(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError("Expected a regular artifact file")
    if not 0 < path.stat().st_size <= 512 * 1024 * 1024:
        raise ValueError("Invalid artifact size")
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify(directory):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    tag = manifest['tag']
    expected = set(names(tag) + ['SHA256SUMS'])
    if set(manifest['files']) != expected:
        raise ValueError("Incomplete or unexpected mirror files")
    base = directory / 'releases' / tag
    if base.is_symlink() or directory.is_symlink() or (directory / 'releases').is_symlink():
        raise ValueError("Mirror directories cannot be symlinks")
    for name, item in manifest['files'].items():
        path = base / name
        if digest(path) != item['sha256'] or path.stat().st_size != item['size']:
            raise ValueError("Mirror artifact differs from manifest")
    return manifest


def stage(tag, assets, output):
    required = names(tag)
    checksums = {}
    for line in (assets / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64}) [ *]([^/\\]+)', line)
        if not match or match[2] in checksums:
            raise ValueError("Invalid or duplicate checksum line")
        checksums[match[2]] = match[1]
    for name in required:
        if digest(assets / name) != checksums.get(name):
            raise ValueError("Release artifact checksum mismatch")
    alias = 'djhx-memo-linux-amd64.deb'
    if digest(assets / alias) != checksums[required[-1]] or checksums.get(alias) != checksums[required[-1]]:
        raise ValueError("Latest DEB alias does not match its versioned file")
    destination = output / 'releases' / tag
    destination.mkdir(parents=True, exist_ok=False)
    manifest = {'version': 1, 'tag': tag, 'files': {}}
    for name in required + ['SHA256SUMS']:
        shutil.copyfile(assets / name, destination / name)
        manifest['files'][name] = {'size': (destination / name).stat().st_size, 'sha256': digest(destination / name)}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    verify(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--assets', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--extract', nargs=2, type=Path, metavar=('ARCHIVE', 'DIRECTORY'))
    args = parser.parse_args()
    if args.extract:
        extract(*args.extract)
    elif args.verify:
        manifest = verify(args.verify)
        print(f"Verified mirror files for {manifest['tag']}")
    elif args.tag and args.assets and args.output:
        stage(args.tag, args.assets, args.output)
        print(f"Staged verified mirror files for {args.tag}")
    else:
        parser.error('Choose --verify or --tag/--assets/--output')


if __name__ == '__main__':
    main()
