cd /home/roland/Dokumente/ChatGPT/chaosbox-android
set -e

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

# Release-APK bauen
/opt/gradle-8.11.1/bin/gradle --no-daemon :app:assembleRelease

# APK ausrichten
/opt/android-sdk/build-tools/35.0.0/zipalign -f -P 16 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

# Mit dem neuen Schlüssel signieren
/opt/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks signing-keys/sshcopy-release.jks \
  --ks-key-alias sshcopy \
  --ks-pass file:signing-keys/sshcopy-release.password \
  --out SSHCopy-release.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

# Signatur prüfen
/opt/android-sdk/build-tools/35.0.0/apksigner verify \
  --verbose --print-certs SSHCopy-release.apk
