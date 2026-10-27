#!/usr/bin/env bash
set -euo pipefail
invocation_dir="$PWD"
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

usage() {
    echo "Verwendung: $0 [--installdir NAME] [--desktop-install PFAD | --installation ID] [--remote-profile NAME] [--ssh-profile NAME] [GERAETE-SERIENNUMMER]"
    echo "Ohne NAME: installiert SSHCopy-debug.apk. Vorher ./build-apk.sh ausführen."
    echo "Mit NAME: baut und installiert eine separate App ChaosBox (NAME)."
    echo "--desktop-install PFAD: Desktop-ID aus PFAD/setup.ini ableiten; Android setup.ini aktualisieren."
    echo "--installation ID: Desktop-ID direkt setzen. --remote-profile NAME: Desktop-Profil wählen."
    echo "--ssh-profile NAME: nur [SSH.NAME] statt des gemeinsamen [SSH]-Abschnitts ändern."
}
installation=""
sync_args=()
serial="${ANDROID_SERIAL:-}"
serial_set=false
while (( $# )); do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --installdir)
            if (( $# < 2 )) || [[ -n "$installation" ]]; then
                usage >&2; exit 1
            fi
            installation="$2"
            shift 2
            if [[ ! "$installation" =~ ^[A-Za-z][A-Za-z0-9_]{0,39}$ ]]; then
                echo "Fehler: NAME muss mit A-Z oder a-z beginnen und darf nur A-Z, a-z, 0-9 und _ enthalten (max. 40 Zeichen)." >&2
                exit 1
            fi
            ;;
        --desktop-install|--installation|--remote-profile|--ssh-profile)
            if (( $# < 2 )) || [[ -z "$2" || "$2" == --* ]]; then usage >&2; exit 1; fi
            value="$2"
            if [[ "$1" == --desktop-install && "$value" != /* ]]; then value="$invocation_dir/$value"; fi
            sync_args+=("$1" "$value")
            shift 2
            ;;
        -*) usage >&2; exit 1 ;;
        *)
            if "$serial_set"; then usage >&2; exit 1; fi
            serial="$1"
            serial_set=true
            shift
            ;;
    esac
done
if (( ${#sync_args[@]} )); then
    /usr/bin/python3 "$PWD/configure-adb-sync.py" "${sync_args[@]}" --android-installdir "${installation:-ChaosBox-default}" --check
fi
apk="$PWD/SSHCopy-debug.apk"
if [[ -n "$installation" ]]; then
    ./build-apk.sh --installdir "$installation"
    apk="$PWD/SSHCopy-debug-$installation.apk"
fi
[[ -f "$apk" ]] || { echo "Fehler: APK fehlt. Zuerst ./build-apk.sh ausführen." >&2; exit 1; }
if command -v adb >/dev/null 2>&1; then
    adb_bin="$(command -v adb)"
elif [[ -x "$PWD/.tooling/android-sdk/platform-tools/adb" ]]; then
    adb_bin="$PWD/.tooling/android-sdk/platform-tools/adb"
elif [[ -x "${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb" ]]; then
    adb_bin="${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb"
else
    echo "Fehler: adb fehlt. Android SDK Platform-Tools installieren." >&2
    exit 1
fi
if [[ -z "$serial" ]]; then
    listing="$("$adb_bin" devices)"
    mapfile -t devices < <(printf '%s\n' "$listing" | awk 'NR > 1 && NF >= 2 {print $1}')
    if (( ${#devices[@]} != 1 )); then
        printf '%s\n' "$listing" >&2
        echo "Bitte genau ein Gerät verbinden oder dessen Seriennummer als Argument angeben." >&2
        exit 1
    fi
    serial="${devices[0]}"
fi
if ! state="$("$adb_bin" -s "$serial" get-state)" || [[ "$state" != "device" ]]; then
    echo "Gerät nicht bereit: USB-Debugging aktivieren und die Freigabe am entsperrten Handy bestätigen." >&2
    exit 1
fi
# Update in place; preserve app data. Do not start the automatic SSH transfer.
if ! "$adb_bin" -s "$serial" install -r "$apk"; then
    echo "Update fehlgeschlagen. Bei INSTALL_FAILED_UPDATE_INCOMPATIBLE muss die APK mit dem ursprünglichen Schlüssel signiert werden. Nicht deinstallieren, wenn die App-Daten erhalten bleiben sollen." >&2
    exit 1
fi
echo "Debug-APK auf $serial installiert."
if (( ${#sync_args[@]} )); then
    /usr/bin/python3 "$PWD/configure-adb-sync.py" "${sync_args[@]}" --android-installdir "${installation:-ChaosBox-default}" --adb "$adb_bin" --serial "$serial"
fi
