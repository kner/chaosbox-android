# ChaosBox — integrierte Android-App

Die App enthält jetzt die vollständige xfComment-Bildbearbeitung, JSON-Anzeige
und den bisherigen SSH-Upload im Paket `local.sshcopy`.

## Bedienung

Die Startansicht ermöglicht die JPG-Auswahl, Box/Kategorie/Kommentar-Eingabe,
EXIF-UserComment-Metadaten und das Speichern. Neue Bilder von außerhalb des JPG-Ordners
werden als auf maximal 100 kB reduzierte Bildkopie importiert. Bilder aus
`ChaosBox/JPG` werden unter demselben Dateinamen atomar überschrieben, ohne
`_cb`-Zusatz und ohne erneute Komprimierung oder Größenbegrenzung. Dabei bleiben
Bilddaten und andere EXIF-Metadaten erhalten. Kommentare werden als UTF-8 mit
undefiniertem EXIF-Zeichensatzpräfix gespeichert; ältere ASCII- und Unicode-Kommentare
bleiben lesbar. Vorhandene Metadaten werden beim Öffnen übernommen.

- Bilder: `/storage/emulated/0/ChaosBox/JPG`
- Konfiguration: `/storage/emulated/0/ChaosBox/Setup/setup.ini`
- JSON-Datensätze: `/storage/emulated/0/ChaosBox/boxes`

Die Kategorie-Liste wird aus dem INI-Abschnitt `[Kategorie]` geladen. Fehlt die
Datei, wird nach Erteilung des Speicherzugriffs die mitgelieferte Vorlage angelegt.
Eine bestehende Konfigurationsdatei wird nicht überschrieben. Gleichnamige neu importierte
Bilder erhalten eine nummerierte Kopie. Android 11+ benötigt Zugriff auf alle Dateien.

Nach jedem erfolgreichen Speichern eines Bilds oder JSON-Datensatzes startet
ein Android-Vordergrunddienst mit Indexaktualisierung und Upload. Die Oberfläche ist
bereits nach dem lokalen Speichern wieder bedienbar; der Dienst läuft unabhängig
von der Editor-Activity. Weitere Speichervorgänge lösen einen erneuten Durchlauf aus,
ohne parallele Uploads zu starten. Bei einem Uploadfehler bleiben die lokalen Daten erhalten;
beim nächsten Speichern wird der Upload erneut versucht. Einen separaten Upload-Button gibt es nicht.

„JSON öffnen“ lädt `ChaosBox/boxes/<Box>.json` anhand des Feldes Box (wahlweise
mit `.json`-Endung). Unterstützt werden einzelne Datensätze, Arrays und Objekte
mit mehreren Datensätzen unter eigenen Schlüsseln. Das editierbare Device-Feld
listet die `device`-Werte auf; eine Auswahl lädt Anzahl, Box, Alias, Kategorie,
Kommentar, Package und Erstellungsdatum des Datensatzes. Ein zuvor geöffnetes
Bild wird dabei geschlossen. Der erste Datensatz wird zunächst angezeigt.
Speichern aktualisiert den bestehenden Datensatz mit demselben Device in der Box-Datei.
Nur ein noch nicht vorhandenes Device erzeugt einen neuen Eintrag. Bei bereits vorhandenen
doppelten Device-Namen wird der ausgewählte Eintrag aktualisiert. Zusätzliche JSON-Felder
und das ursprüngliche Erstellungsdatum bleiben erhalten; count/anzahl und pack/package
werden beim Aktualisieren konsistent gehalten.

Beim automatischen Upload werden Unterordner ausgelassen;
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
Das ehemalige xfComment-Projekt ist als Quellcode-Archiv unter
`backups/xfcomment-before-integration.tar.gz` gesichert.

---

## Frühere SSH-Copy-Dokumentation

Die folgenden Angaben beschreiben teilweise den früheren Stand; für Startansicht
und Pfade gelten die Angaben oben.

# SSH Copy for Android

Opening the app automatically uploads the immediate regular files in
`/storage/emulated/0/source` to `x@hostname:22`, directory `y` relative to the SSH
user's home. The remote directory must already exist. Hidden files are included;
subdirectories and symbolic links are skipped. Existing remote files of the same
name are overwritten. Source files are never deleted. Transfers use SFTP over SSH.

There is no Copy button, folder picker, or connection form. Android requires a
one-time storage-access grant. Keep the app open until it reports completion.
Opening a new app instance starts another transfer; it is not a boot/background scheduler.
If interrupted, completed files remain copied and the current remote file may be
partial; reopening copies the files again.

## Hardcoded settings

Edit `app/src/main/java/local/sshcopy/Config.java` and rebuild to replace the supplied
literal placeholders `source`, `hostname`, `x`, `y`. Relative source paths resolve
under shared internal storage. Absolute source paths are also supported, subject
to Android access restrictions.

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

On this workspace the toolchain is already downloaded: `./build-apk.sh` builds,
lints, and copies the result to `SSHCopy-debug.apk`. The SDK is stored persistently in `.tooling/android-sdk`.
`local.properties` points to that directory; `build-apk.sh` also sets `ANDROID_HOME`.

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

## Upload-Ziele ab 2.5

Bilder aus ChaosBox/JPG gehen nach `storage/app/exif/jpg`, JSON-Dateien aus ChaosBox/boxes nach `storage/app/exif/data`, jeweils relativ zum SFTP-Startverzeichnis. Beide Serverordner müssen existieren und schreibbar sein. Fehlende oder lokal neuere Dateien werden übertragen.

## Gemeinsame Datendatei und Suche

`ChaosBox/data/records.json` enthält die Datensätze aller JPG/JPEG-Dateien in
`ChaosBox/JPG` und aller JSON-Dateien in `ChaosBox/boxes`, einschließlich
Unterordnern. `path` enthält jeweils den Quelldateinamen. Die Datei wird beim
App-Start, nach jedem Speichern und vor der Suche vollständig und atomar erneuert.
Fehlerhafte Quellen werden gemeldet; die bisherige Datendatei bleibt erhalten.

Die Lupe schaltet auf leere Suchfelder um. Device und Kategorie sind dann reine
Texteingaben; Speichern ist gesperrt. Ein zweiter Klick startet die Suche. Reguläre
Ausdrücke gelten als Teiltreffer ohne Beachtung der Groß-/Kleinschreibung, mehrere
Felder werden mit UND verknüpft, leere Felder ignoriert. `^...$` sucht einen ganzen
Feldinhalt. Ungültige Ausdrücke werden am Feld angezeigt. Zurück bricht die Suche ab.
Ein einzelner Treffer wird direkt geladen, mehrere Treffer stehen zur Auswahl.
Danach ist die normale Bearbeitung wieder aktiv. Bei Bildtreffern wird das Bild
geöffnet, bei JSON-Treffern wird ein Bild mit gleicher Box und gleichem Device
angezeigt, sofern vorhanden; gespeichert wird weiterhin ein JSON-Datensatz.

Suchfelder: Alias und Kommentar durchsuchen jeweils Device, Alias und Kommentar.
Device durchsucht Device und Kommentar; Kategorie durchsucht Kategorie und Kommentar. Pro Eingabe genügt ein Treffer in
einem dieser Zielfelder; mehrere ausgefüllte Suchfelder bleiben UND-verknüpft.
