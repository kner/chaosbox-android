#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    echo "Verwendung: $0 [GERAETE-SERIENNUMMER]"
    echo "Installiert SSHCopy-debug.apk. Vorher ./build-apk.sh ausführen."
    exit 0
fi
if (( $# > 1 )); then
    echo "Verwendung: $0 [GERAETE-SERIENNUMMER]" >&2
    exit 1
fi
apk="$PWD/SSHCopy-debug.apk"
[[ -f "$apk" ]] || { echo "Fehler: APK fehlt. Zuerst ./build-apk.sh ausführen." >&2; exit 1; }
if command -v adb >/dev/null 2>&1; then
    adb_bin="$(command -v adb)"
elif [[ -x "${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb" ]]; then
    adb_bin="${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb"
else
    echo "Fehler: adb fehlt. Android SDK Platform-Tools installieren." >&2
    exit 1
fi
serial="${1:-${ANDROID_SERIAL:-}}"
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
"$adb_bin" -s "$serial" install -r "$apk"
echo "Debug-APK auf $serial installiert."
