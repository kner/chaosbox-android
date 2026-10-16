#!/usr/bin/env python3
"""Publish explicit server-side deletions to the ChaosBox v1 journal."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import uuid


def delete(data, paths, apply=False):
    root = data / 'TXT' / '.chaosbox-sync'
    if not (root / 'ops').is_dir():
        raise ValueError('No existing sync journal at ' + str(root))
    targets = set()
    for name in paths:
        path = PurePosixPath(name)
        if (path.is_absolute() or len(path.parts) < 2 or
                path.parts[0] not in ('JPG', 'TXT') or '\\' in name or
                any(p.startswith('.') for p in path.parts) or
                any(ord(c) < 32 for c in name) or str(path) != name):
            raise ValueError('Use a relative JPG/... or TXT/... path: ' + name)
        targets.add(('media' if path.parts[0] == 'JPG' else 'record',
                     '/'.join(path.parts[1:])))
    lock = root / 'transfer.lock'
    lock.mkdir()  # Same exclusion used by Desktop and Android; never steal a lock.
    try:
        ops = {}
        for file in sorted((root / 'ops').glob('*.op')):
            op = json.loads(file.read_text(encoding='utf-8'))
            if op['schema'] != 1 or file.stem != op['revision']:
                raise ValueError('Invalid journal operation: ' + str(file))
            ops[op['revision']] = op
        referenced = set()
        for op in ops.values():
            for revision in op['parents']:
                parent = ops.get(revision)
                if parent is None or (parent['id'], parent['kind']) != (op['id'], op['kind']):
                    raise ValueError('Incomplete journal; refusing deletion')
                referenced.add(revision)
        heads = {}
        for revision, op in ops.items():
            if revision not in referenced:
                heads.setdefault(op['id'], []).append(op)
        selected = []
        for versions in heads.values():
            if not any((o['kind'], o['path']) in targets for o in versions):
                continue
            if len(versions) != 1:
                raise ValueError('Resolve conflicting object first: ' + versions[0]['id'])
            if versions[0]['action'] == 'put':
                selected.append(versions[0])
        for kind, path in sorted(targets):
            count = sum((o['kind'], o['path']) == (kind, path) for o in selected)
            print(f'{kind} {path}: {count} deletion(s)' + ('' if apply else ' (preview)'))
        if apply:
            for old in selected:
                revision = str(uuid.uuid4())
                op = dict(schema=1, id=old['id'], revision=revision,
                          parents=[old['revision']], kind=old['kind'], path=old['path'],
                          action='delete', device_id='server-delete',
                          issued_at=datetime.now(timezone.utc).isoformat())
                payload = json.dumps(op, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
                temporary = root / (revision + '.tmp')
                try:
                    with temporary.open('xb') as stream:
                        stream.write(payload)
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(temporary, root / 'ops' / (revision + '.op'))
                finally:
                    temporary.unlink(missing_ok=True)
            # The next normal sync rebuilds caches and updates server projections.
            (root / 'checksums.json').unlink(missing_ok=True)
            (root / 'media-checkpoint.json').unlink(missing_ok=True)
        return len(selected)
    finally:
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', required=True, type=Path, help='Server profile root containing JPG and TXT')
    parser.add_argument('--delete', required=True, nargs='+', metavar='PATH', help='Explicit JPG/file.jpg or TXT/box.json paths; files may already be missing')
    parser.add_argument('--apply', action='store_true', help='Publish deletions; default is preview only')
    args = parser.parse_args()
    try:
        delete(args.data, args.delete, args.apply)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Error: {error}\n')


if __name__ == '__main__':
    main()
