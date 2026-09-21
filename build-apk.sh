#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

# Build a signed development APK using the system-wide toolchain.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for jdk in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64; do
        if [[ -x "$jdk/bin/javac" ]]; then
            export JAVA_HOME="$jdk"
            break
        fi
    done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null || { echo "Fehler: JDK 17 oder neuer fehlt." >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
gradle="${GRADLE_HOME:-/opt/gradle-8.11.1}/bin/gradle"
[[ -x "$gradle" ]] || { echo "Fehler: Gradle fehlt: $gradle" >&2; exit 1; }
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || {
    echo "Fehler: Android SDK Platform 35 fehlt in $ANDROID_HOME." >&2
    exit 1
}
"$gradle" --no-daemon :app:assembleDebug :app:lintDebug --rerun-tasks
cp -- app/build/outputs/apk/debug/app-debug.apk SSHCopy-debug.apk
printf '\nDebug-APK: %s/SSHCopy-debug.apk\n' "$PWD"
