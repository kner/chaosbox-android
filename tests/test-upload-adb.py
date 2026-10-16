"""Exercise upload and configuration against a fake ADB device, without a phone."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configure', ROOT / 'configure-adb-sync.py')
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)


class SetupTests(unittest.TestCase):
    def test_preserves_other_sections_and_multiline_text(self):
        original = '[SSH]\r\nHost=server\r\n\r\n[TextSnippets]\r\nx="hello\r\nworld"\r\n'
        result = configure.update_setup(original, 'desktop-one')
        self.assertEqual(result, original.replace('\r\n[TextSnippets]', '\r\nInstallation=desktop-one\r\n[TextSnippets]'))
        self.assertEqual(configure.update_setup(result, 'desktop-one'), result)

    def test_profile_override_and_duplicates(self):
        original = '[SSH]\nInstallation=base\n[SSH.Work]\ninstallation=old\nInstallation=older\n[App.Work]\nTitel=Work'
        result = configure.update_setup(original, 'new', 'bilderbox', 'work')
        self.assertIn('[SSH]\nInstallation=base\n', result)
        self.assertEqual(result.count('Installation=new'), 2)
        self.assertIn('RemoteProfile=bilderbox\n[App.Work]', result)
        self.assertTrue(result.endswith('Titel=Work'))

    def test_missing_section_and_final_newline(self):
        result = configure.update_setup('[App]\nStandard=Work', 'new', ssh_profile='Work')
        self.assertEqual(result, '[App]\nStandard=Work\n\n[SSH.Work]\nInstallation=new\n')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='chaosbox-upload-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in ('upload-adb-debug.sh', 'upload-adb.sh', 'configure-adb-sync.py'):
            shutil.copy2(ROOT / name, self.root / name)
        asset = self.root / 'app/src/main/assets/initial/Setup/setup.ini'
        asset.parent.mkdir(parents=True)
        asset.write_text('[App]\nStandard=Chaosbox\n[SSH]\nHost=server\n')
        (self.root / 'SSHCopy-debug.apk').touch()
        build = self.root / 'build-apk.sh'
        build.write_text('#!/bin/bash\ntouch "SSHCopy-debug-$2.apk"\n')
        build.chmod(0o755)
        adb = self.root / 'adb'
        adb.write_text('''#!/usr/bin/python3
import os,sys,subprocess,shutil
from pathlib import Path
args=sys.argv[1:]
if args[:1]==['-s']:
 assert args[1]=='test-device',args
 args=args[2:]
root=Path(os.environ['FAKE_DEVICE'])
def local(path):return str(root)+path[len('/sdcard'):] if path.startswith('/sdcard') else path
if args[0]=='devices':print('List of devices attached\\ntest-device\\tdevice')
elif args[0]=='get-state':print('device')
elif args[0]=='install':(root/'installed').touch()
elif args[0] in ('pull','push'):
 if args[0]=='push' and os.environ.get('FAIL_PUSH'):sys.exit(1)
 shutil.copyfile(local(args[1]),local(args[2]))
elif args[0]=='shell':
 sys.exit(subprocess.run(args[1].replace('/sdcard',str(root)),shell=True).returncode)
else:sys.exit(3)
''')
        adb.chmod(0o755)
        self.device = self.root / 'device'
        self.device.mkdir()
        self.setup = self.device / 'ChaosBox-default/setup.ini'
        self.setup.parent.mkdir(parents=True)
        self.env = dict(os.environ, PATH=str(self.root)+':'+os.environ['PATH'], FAKE_DEVICE=str(self.device))

    def upload(self, *args, success=True):
        result = subprocess.run(['bash', str(self.root / 'upload-adb.sh'), *args], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def test_wrapper_updates_existing_file_with_backup(self):
        self.setup=self.device/'work/setup.ini'
        self.setup.parent.mkdir(parents=True)
        original = '[SSH]\r\nHost=example\r\nInstallation=old\r\n[App.Work]\r\nTitel=My work\r\n'
        self.setup.write_bytes(original.encode())
        self.upload('--installdir', 'work', '--installation', 'desktop-one', '--remote-profile', 'bilderbox', 'test-device')
        result = self.setup.read_bytes().decode()
        self.assertIn('Installation=desktop-one\r\n', result)
        self.assertIn('RemoteProfile=bilderbox\r\n', result)
        self.assertIn('[App.Work]\r\nTitel=My work\r\n', result)
        self.assertEqual(next(self.setup.parent.glob('*.bak')).read_bytes(), original.encode())
        self.upload('--installdir', 'work', '--installation', 'desktop-one', '--remote-profile', 'bilderbox')
        self.assertEqual(len(list(self.setup.parent.glob('*.bak'))), 1)

    def test_first_install_uses_seed(self):
        self.upload('--installation', 'desktop-two')
        self.assertIn('Host=server\nInstallation=desktop-two\n', self.setup.read_text())

    def test_invalid_options_stop_before_install(self):
        for args in (('--installation', '../bad'), ('--remote-profile', 'bilderbox'),
                     ('--installation', 'one', '--desktop-install', '/missing'),
                     ('--desktop-install', '/missing'), ('--installation', 'one', '--remote-profile', '../bad')):
            self.upload(*args, success=False)
            self.assertFalse((self.device / 'installed').exists())

    def test_failed_push_keeps_existing_config(self):
        self.setup.write_text('[SSH]\nInstallation=old\n')
        self.env['FAIL_PUSH'] = '1'
        self.upload('--installation', 'new', success=False)
        self.assertEqual(self.setup.read_text(), '[SSH]\nInstallation=old\n')
        self.assertEqual(list(self.setup.parent.glob('*.tmp')), [])


if __name__ == '__main__':
    unittest.main()
