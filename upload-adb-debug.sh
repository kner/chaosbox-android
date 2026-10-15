#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

usage() {
    echo "Verwendung: $0 [--installdir NAME] [GERAETE-SERIENNUMMER]"
    echo "Ohne NAME: installiert SSHCopy-debug.apk. Vorher ./build-apk.sh ausführen."
    echo "Mit NAME: baut und installiert eine separate App ChaosBox (NAME)."
}
installation=""
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
            if [[ ! "$installation" =~ ^[a-z][a-z0-9_]{0,39}$ ]]; then
                echo "Fehler: NAME muss mit a-z beginnen und darf nur a-z, 0-9 und _ enthalten (max. 40 Zeichen)." >&2
                exit 1
            fi
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
