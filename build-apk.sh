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
if [[ -d "$PWD/.tooling/android-sdk" ]]; then
    default_android_sdk="$PWD/.tooling/android-sdk"
else
    default_android_sdk="/opt/android-sdk"
fi
export ANDROID_HOME="${ANDROID_HOME:-$default_android_sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -x "$PWD/.tooling/gradle-8.11.1/bin/gradle" ]]; then
    default_gradle_home="$PWD/.tooling/gradle-8.11.1"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tooling/gradle-user-home}"
else
    default_gradle_home="/opt/gradle-8.11.1"
fi
gradle="${GRADLE_HOME:-$default_gradle_home}/bin/gradle"
[[ -x "$gradle" ]] || { echo "Fehler: Gradle fehlt: $gradle" >&2; exit 1; }
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || {
    echo "Fehler: Android SDK Platform 35 fehlt in $ANDROID_HOME." >&2
    exit 1
}
"$gradle" --no-daemon :app:assembleDebug :app:lintDebug --rerun-tasks
cp -- app/build/outputs/apk/debug/app-debug.apk SSHCopy-debug.apk
printf '\nDebug-APK: %s/SSHCopy-debug.apk\n' "$PWD"
