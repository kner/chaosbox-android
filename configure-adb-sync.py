#!/usr/bin/env python3
"""Set an Android SSH sync target without replacing unrelated setup.ini settings."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent


def installation_id(directory):
    directory = Path(directory).expanduser().resolve()
    if not (directory / 'setup.ini').is_file():
        raise ValueError(f'Desktop setup.ini fehlt: {directory}')
    desktop = ROOT.parent / 'chaosbox-desktop'
    # Use Desktop's implementation rather than duplicating its machine/path ID algorithm.
    result = subprocess.run([sys.executable, '-B', '-c',
                             'import sys; sys.path.insert(0, sys.argv[1]); import core; '
                             'print(core.Settings(sys.argv[2]).installation_id)',
                             str(desktop), str(directory)], capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Desktop-ID konnte nicht gelesen werden. Das Geschwisterprojekt chaosbox-desktop und dessen Python-Abhängigkeiten werden benötigt.')
    return result.stdout.strip()


def update_setup(text, installation, remote_profile=None, ssh_profile=None):
    section = 'SSH' + ('.' + ssh_profile if ssh_profile else '')
    updates = {'installation': ('Installation', installation)}
    if remote_profile is not None:
        updates['remoteprofile'] = ('RemoteProfile', remote_profile)
    newline = '\r\n' if '\r\n' in text else '\n'
    lines = text.splitlines(keepends=True)
    output, seen = [], set()
    active = found = False

    def append_missing():
        for key, (label, value) in updates.items():
            if key not in seen:
                if output and not output[-1].endswith(('\n', '\r')):
                    output[-1] += newline
                output.append(f'{label}={value}{newline}')
                seen.add(key)

    for line in lines:
        heading = re.fullmatch(r'\[([^\]]+)\]', line.strip().lstrip('\ufeff'))
        if heading:
            if active:
                append_missing()
            active = heading[1].casefold() == section.casefold()
            found |= active
        key = re.match(r'\s*([^=;#]+?)\s*=', line) if active else None
        name = key[1].casefold() if key else ''
        if name in updates:
            label, value = updates[name]
            output.append(f'{label}={value}{newline}')
            seen.add(name)
        else:
            output.append(line)
    if not found:
        if output and not output[-1].endswith(('\n', '\r')):
            output[-1] += newline
        output.append(f'{newline}[{section}]{newline}')
    append_missing()
    return ''.join(output)


def configure(args, installation):
    remote = f"/sdcard/{args.android_installdir}/setup.ini"
    def adb(*command, **kwargs):
        return subprocess.run([args.adb, '-s', args.serial, *command], check=True, **kwargs)

    with tempfile.TemporaryDirectory(prefix='chaosbox-setup-') as temporary:
        local = Path(temporary) / 'setup.ini'
        probe = subprocess.run([args.adb, '-s', args.serial, 'shell',
                                f'if [ -f {remote} ]; then echo exists; elif [ ! -e {remote} ] && [ -d /sdcard ]; then echo missing; else exit 1; fi'],
                               check=True, capture_output=True, text=True)
        exists = probe.stdout.strip() == 'exists'
        if not exists and probe.stdout.strip() != 'missing':
            raise ValueError('Status der Android-Setup-Datei konnte nicht gelesen werden.')
        if exists:
            adb('pull', remote, str(local), capture_output=True)
            text = local.read_bytes().decode('utf-8')
        else:
            text = (ROOT / 'app/src/main/assets/initial/Setup/setup.ini').read_bytes().decode('utf-8')
        updated = update_setup(text, installation, args.remote_profile, args.ssh_profile)
        if exists and text == updated:
            print('Android setup.ini: Synchronisationsziel ist bereits korrekt.')
            return
        local.write_bytes(updated.encode('utf-8'))
        token = uuid.uuid4().hex
        staging = remote + '.' + token + '.tmp'
        backup = remote + '.' + token + '.bak'
        adb('shell', f"mkdir -p /sdcard/{args.android_installdir}", capture_output=True)
        try:
            adb('push', str(local), staging, capture_output=True)
            # Preserve the exact previous file and detect changes made while editing.
            if exists:
                previous = Path(temporary) / 'previous.ini'
                adb('pull', remote, str(previous), capture_output=True)
                if previous.read_bytes() != text.encode('utf-8'):
                    raise ValueError('Android setup.ini wurde inzwischen geändert. Bitte erneut ausführen.')
                adb('shell', f'cp {remote} {backup} && mv {staging} {remote}', capture_output=True)
                print('Backup auf Android: ' + backup)
            else:
                adb('shell', f'test ! -e {remote} && mv {staging} {remote}', capture_output=True)
        finally:
            subprocess.run([args.adb, '-s', args.serial, 'shell', f'rm -f {staging}'], capture_output=True)
        print(f'Android [{"SSH." + args.ssh_profile if args.ssh_profile else "SSH"}]: Installation={installation}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--installation')
    target.add_argument('--desktop-install', type=Path)
    parser.add_argument('--android-installdir', default='ChaosBox-default')
    parser.add_argument('--remote-profile')
    parser.add_argument('--ssh-profile')
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        if args.android_installdir != 'ChaosBox-default' and not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,39}', args.android_installdir):
            raise ValueError('Ungültiges Android-Installationsverzeichnis.')
        installation = installation_id(args.desktop_install) if args.desktop_install else args.installation
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,79}', installation):
            raise ValueError('Ungültige Installations-ID.')
        if args.remote_profile is not None and any(not part or part.startswith('.') or '\\' in part or any(ord(c) < 32 or ord(c) == 127 for c in part) for part in args.remote_profile.split('/')):
            raise ValueError('Ungültiges RemoteProfile.')
        if args.ssh_profile is not None and (not args.ssh_profile.strip() or any(c in args.ssh_profile for c in '[]\r\n') or any(ord(c) < 32 for c in args.ssh_profile)):
            raise ValueError('Ungültiges SSH-Profil.')
        if not args.check:
            if not args.adb or not args.serial:
                raise ValueError('--adb und --serial fehlen.')
            configure(args, installation)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Fehler beim Konfigurieren von Android setup.ini: {error}\n')


if __name__ == '__main__':
    main()
