#!/usr/bin/env python3
"""XMP exchange with the reference desktop project.
Run: python3 tests/test-xmp.py --json-jar /path/to/org.json.jar
Requires desktop dependencies (GExiv2, Pillow), ExifTool and a JDK.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--json-jar', type=Path, required=True)
parser.add_argument('--desktop', type=Path, default=Path(__file__).resolve().parents[2] / 'chaosbox-desktop')
args = parser.parse_args()
sys.path.insert(0, str(args.desktop.resolve()))
import core
from PIL import Image

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='chaosbox-xmp-') as temporary:
    folder = Path(temporary)
    subprocess.run(['javac', '-cp', str(args.json_jar), '-d', str(folder),
                    str(root / 'app/src/main/java/local/sshcopy/XmpRecords.java'),
                    str(root / 'app/src/main/java/local/sshcopy/JpegComments.java'),
                    str(root / 'tests/XmpRecordsTest.java')], check=True)
    def java(*arguments):
        subprocess.run(['java', '-cp', f'{folder}:{args.json_jar}', 'local.sshcopy.XmpRecordsTest', *map(str, arguments)], check=True)
    java()
    source = folder / 'source.jpg'
    Image.new('RGB', (20, 20)).save(source)
    subprocess.run(['exiftool', '-overwrite_original', '-XMP-dc:Title=Keep me', '-EXIF:UserComment=Old comment', str(source)], check=True, capture_output=True)
    record = {field: '' for field in (*core.FIELDS, 'created')}
    record.update(box='A11', anzahl=3, category='TEST', comment='Grüße 東京 & < >\nZeile', created='2026-10-14T12:00:00+02:00')
    target = folder / 'android.jpg'
    Path(str(target)+'.json').write_text(json.dumps(record), encoding='utf-8')
    java('write', source, target)
    assert not subprocess.check_output(['exiftool', '-s3', '-EXIF:UserComment', str(target)], text=True).strip()
    actual = core.read_metadata(target)
    assert all(str(actual[k]) == str(v) for k, v in record.items()), actual
    assert subprocess.check_output(['exiftool', '-s3', '-XMP-dc:Title', str(target)], text=True).strip() == 'Keep me'
    desktop = folder / 'desktop.jpg'
    shutil.copy(source, desktop)
    core.write_image_metadata(source, desktop, record)
    java('read', desktop, folder / 'read.json')
    actual = json.loads((folder / 'read.json').read_text())
    assert all(str(actual[k]) == str(v) for k, v in record.items()), actual
    print('PASS: Android ↔ Desktop, Unicode, dates, empty fields and unrelated XMP preservation')
