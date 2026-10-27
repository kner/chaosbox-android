#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

installation=""
gradle_args=()
if (( $# )); then
  if [[ $# == 1 ]]; then
    installation="$1"
  elif [[ $# == 2 && "$1" == "--installdir" ]]; then
    installation="$2"
  else
    echo "Verwendung: $0 [NAME | --installdir NAME]" >&2
    exit 1
  fi
  if [[ ! "$installation" =~ ^[A-Za-z][A-Za-z0-9_]{0,39}$ ]]; then
    echo "Ungültiger Name: A-Z/a-z, dann A-Z/a-z/0-9/_, max. 40 Zeichen" >&2
    exit 1
  fi
  gradle_args+=("-PinstallationName=$installation")
fi
version="$(tr -d '[:space:]' < VERSION)"
apk="released/KBox-${installation:-release}-${version//./}.apk"

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -x "$PWD/.tooling/gradle-8.11.1/bin/gradle" ]]; then
  default_gradle_home="$PWD/.tooling/gradle-8.11.1"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tooling/gradle-user-home}"
else
  default_gradle_home="/opt/gradle-8.11.1"
fi
gradle="${GRADLE_HOME:-$default_gradle_home}/bin/gradle"
[[ -x "$gradle" ]] || { echo "Fehler: Gradle fehlt: $gradle" >&2; exit 1; }

if [[ -d "$PWD/.tooling/android-sdk" ]]; then
  default_android_sdk="$PWD/.tooling/android-sdk"
else
  default_android_sdk="/opt/android-sdk"
fi
export ANDROID_HOME="${ANDROID_HOME:-$default_android_sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
build_tools="$ANDROID_HOME/build-tools/35.0.0"

# Release-APK bauen
"$gradle" "${gradle_args[@]}" --no-daemon :app:assembleRelease

# APK ausrichten
"$build_tools/zipalign" -f -P 16 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

# Mit dem neuen Schlüssel signieren
mkdir -p -- released
"$build_tools/apksigner" sign \
  --ks signing-keys/sshcopy-release.jks \
  --ks-key-alias sshcopy \
  --ks-pass file:signing-keys/sshcopy-release.password \
  --out "$apk" \
  app/build/outputs/apk/release/app-release-aligned.apk

# Signatur prüfen
"$build_tools/apksigner" verify \
  --verbose --print-certs "$apk"
printf '\nRelease-APK: %s/%s\n' "$PWD" "$apk"
