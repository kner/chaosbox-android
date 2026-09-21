# ChaosBox — integrierte Android-App

Die App enthält jetzt die vollständige xfComment-Bildbearbeitung, JSON-Anzeige
und den bisherigen SSH-Upload im Paket `local.sshcopy`.

## Bedienung

Die Startansicht ermöglicht die JPG-Auswahl, Box/Kategorie/Kommentar-Eingabe,
EXIF-UserComment-Metadaten und das Speichern einer auf maximal 100 kB reduzierten
Bildkopie. Vorhandene Metadaten werden beim Öffnen übernommen.

- Bilder: `/storage/emulated/0/ChaosBox/JPG`
- Konfiguration: `/storage/emulated/0/ChaosBox/Setup/setup.ini`
- JSON-Datensätze: `/storage/emulated/0/ChaosBox/boxes`

Die Kategorie-Liste wird aus dem INI-Abschnitt `[Kategorie]` geladen. Fehlt die
Datei, wird nach Erteilung des Speicherzugriffs die mitgelieferte Vorlage angelegt.
Eine bestehende Datei wird nicht überschrieben. Gleichnamige Bilder erhalten
eine nummerierte Kopie. Android 11+ benötigt Zugriff auf alle Dateien.

Über „Datensätze und SSH-Upload“ öffnest du die JSON-Anzeige und startest die
bisherige Übertragung der Dateien aus JPG. Unterordner werden ausgelassen;
Bilder aus JPG und JSON-Dateien aus boxes werden in den konfigurierten
Server-Zielordner hochgeladen, wenn sie fehlen oder lokal neuer sind. Gleich alte
oder neuere Serverdateien bleiben erhalten. Verglichen wird die Änderungszeit
in ganzen Sekunden; nach dem Upload wird die lokale Änderungszeit übernommen. Servereinstellungen stehen weiterhin in Config.java; SSH-Key
und known_hosts bleiben im privaten Speicher der App erhalten.

## Build und Installation

```bash
./build-apk.sh
./upload-adb.sh
```

Ausgabe: `SSHCopy-debug.apk` (App-Name auf dem Smartphone: ChaosBox).
Das frühere xfComment-Quellcode-Archiv und entfernte Dropbox-Konfliktkopien
sind weiterhin in der Git-Historie verfügbar.

---


## SSH credentials (one-time local setup)

The app expects an **unencrypted** SSH private key named `z` and an OpenSSH
`known_hosts` file. It uses strict host-key verification and public-key-only login.
No real private key is included in the delivered APK. Provision a dedicated key;
its public key must be authorized on the server. Verify the server host key with
your server administrator before adding it to `known_hosts`.

For the supplied debug APK, enable USB debugging, connect your phone, and run:

```bash
./upload-adb.sh
adb shell run-as local.sshcopy mkdir -p files
adb shell run-as local.sshcopy sh -c '"cat > files/z"' < /absolute/local/path/to/private_key
adb shell run-as local.sshcopy sh -c '"cat > files/known_hosts"' < /absolute/local/path/to/known_hosts
adb shell run-as local.sshcopy chmod 600 files/z files/known_hosts
```

These commands place credentials in the app's private storage without a picker.
Alternatively, before building, place those two files in `app/src/main/assets/`.
Bundled private keys can be extracted from the APK, so keep such an APK private.
The files are ignored by Git. Passphrase-protected keys are not supported in this
version because it has no credential prompt.

Then create/populate `/storage/emulated/0/source`, open SSH Copy, and grant the
requested storage permission. This APK requires Android 8.0 or newer. Android's
protected app directories remain inaccessible even with all-files access.

## Build

Install JDK 17+, Android SDK platform 35/build tools 35.0.0, and Gradle 8.11.1.
Set `sdk.dir` in `local.properties` to your SDK path, then run:

```bash
gradle :app:assembleDebug :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The delivered APK is a
sideloadable development build; it is not signed for Play Store publication.

Libraries: [JSch](https://github.com/mwiede/jsch) and
[Bouncy Castle](https://www.bouncycastle.org/).
Storage behavior: [Android documentation](https://developer.android.com/training/data-storage/manage-all-files).

On this workspace the toolchain is installed system-wide: the Android SDK is in
`/opt/android-sdk` and Gradle 8.11.1 is in `/opt/gradle-8.11.1`.
`./build-apk.sh` builds, lints, and copies the result to `SSHCopy-debug.apk`.
Set `sdk.dir=/opt/android-sdk` in `local.properties`. The build script defaults
to these system paths; `ANDROID_HOME` and `GRADLE_HOME` can override them
(keep `local.properties` consistent with the SDK path).
Gradle and Android use their normal per-user directories (`~/.gradle` and
`~/.android`), or explicitly configured `GRADLE_USER_HOME` and `ANDROID_USER_HOME`.
When migrating an existing development setup, preserve its debug signing key
as `~/.android/debug.keystore` to keep APK updates compatible.

File-selection regression check:

```bash
mkdir -p build/test-classes
javac -d build/test-classes app/src/main/java/local/sshcopy/SourceFiles.java tests/SourceFilesTest.java
java -cp build/test-classes local.sshcopy.SourceFilesTest
```

## Debug build and installation via ADB

```bash
./build-apk.sh
./upload-adb.sh
```

Enable USB debugging and authorize the computer on the phone. With multiple
devices connected, use `./upload-adb.sh SERIAL` (see `adb devices`). The script
also accepts `ANDROID_SERIAL`. Installation preserves app data and does not
launch the app. A differently signed installed version cannot be updated in
place; the script reports the ADB error and does not uninstall it.

## ChaosBox storage

The upload source is `/storage/emulated/0/ChaosBox/boxen`. Only immediate
regular files are uploaded; subdirectories are excluded. Grant SSH Copy
all-files access in Android settings. Existing files in Pictures/cbox are
not moved automatically.

The intended configuration location is `/storage/emulated/0/ChaosBox/setup/setup.ini`.
INI parsing is not implemented yet; connection settings still come from Config.java.

Über „Setup bearbeiten“ lässt sich die setup.ini direkt in der App ändern und speichern. Die Kategorien werden danach neu geladen.
# chaosbox-android
