#!/usr/bin/env python3
"""Exercise server deletions using the desktop protocol implementation."""
import importlib.util
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / 'chaosbox-desktop'))
import chaos_sync
spec = importlib.util.spec_from_file_location('server_delete', ROOT / 'scripts/server-delete.py')
server_delete = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server_delete)

with tempfile.TemporaryDirectory() as temporary:
    data = Path(temporary)
    (data / 'TXT').mkdir()
    (data / 'JPG').mkdir()
    (data / 'TXT/box.json').write_text('[{"comment":"one"},{"comment":"two"}]')
    store = chaos_sync.Store(data / 'JPG', data / 'TXT')
    store.capture()
    # Simulates an operator having already removed the server JSON file.
    (data / 'TXT/box.json').unlink()
    before = store.operations()
    assert server_delete.delete(data, ['TXT/box.json']) == 2
    assert before == store.operations()
    assert server_delete.delete(data, ['TXT/box.json'], True) == 2
    store.apply()
    assert not (data / 'TXT/box.json').exists() or (data / 'TXT/box.json').read_text().strip() == '[]'
    assert server_delete.delete(data, ['TXT/box.json'], True) == 0
    try:
        server_delete.delete(data, ['TXT/../box.json'], True)
        raise AssertionError('Unsafe path accepted')
    except ValueError:
        pass
    lock = store.root / 'transfer.lock'
    lock.mkdir()
    try:
        server_delete.delete(data, ['TXT/box.json'], True)
        raise AssertionError('Concurrent transfer accepted')
    except FileExistsError:
        pass
    assert lock.is_dir()
print('PASS: preview, missing JSON, protocol application, idempotence, path checks and transfer lock')
