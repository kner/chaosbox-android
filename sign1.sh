keytool -genkeypair -v \
  -keystore sshcopy-new-release.jks \
  -alias sshcopy \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=SSHCopy"
