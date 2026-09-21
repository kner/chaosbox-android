#signieren einer release-Version des apk
#1.Schritt:
#keytool -genkeypair -v \
#  -keystore sshcopy-release.jks \
#  -alias sshcopy \
#  -keyalg RSA -keysize 2048 -validity 10000 \
#  -dname "CN=SSHCopy"

#2. Schritt
  BUILD_TOOLS="${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0"
  "$BUILD_TOOLS/zipalign" -f -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  SSHCopy-aligned.apk
#3.Schritt
  "$BUILD_TOOLS/apksigner" sign \
  --ks sshcopy-release.jks \
  --ks-key-alias sshcopy \
  --out SSHCopy.apk \
  SSHCopy-aligned.apk

  "$BUILD_TOOLS/apksigner" verify --verbose SSHCopy.apk
#4. Schritt: installieren
 # adb install -r SSHCopy.apk
