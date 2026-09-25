# ChaosBox — Android app

## English interface and form actions

All application labels, dialogs, hints, error messages and source comments are in
English. Existing standard field labels (`Anzahl`, `Kategorie`, `Kommentar`) are
displayed as `Quantity`, `Category`, `Comment`, including in existing setups.
Custom profile labels, categories, snippets and record contents remain user data;
existing configuration and JSON keys remain compatible.

The form action row contains three equally sized controls:

- **Clear all** (icon): clears the form and selected image or record.
- **Repeat last search** (return arrow): immediately reruns the last valid search,
  including after opening a result or clearing the form. It stays disabled until
  a search has run and resets when the active profile changes.
- **TXT**: opens the text snippet picker and copies the selected snippet.

Tap Search to enter search mode, enter regular expressions, then tap Search again.
The system Back action cancels search mode and restores the previous form.
Search history is retained during the current activity only.


Die App enthält jetzt die vollständige xfComment-Bildbearbeitung, JSON-Anzeige
und den bisherigen SSH-Upload im Paket `local.sshcopy`.

## Speicherpfade und Kategorieordner

In `ChaosBox/Setup/setup.ini` werden die Basisordner konfiguriert:

```ini
[App]
Standard=Chaosbox

[App.Chaosbox]
Titel=Chaosbox
JPG=ChaosBox/JPG
Daten=ChaosBox/boxes
Felder=Box,Anzahl,Device,Alias,Kategorie,Kommentar,Package
Kategorie=Heizung, Wasser, Elektro, Maurer
```

Relative Pfade beziehen sich auf den gemeinsamen internen Android-Speicher;
absolute Pfade sind ebenfalls möglich. Jedes App-Profil definiert eigene Bild- und
Datenpfade. Die Auswahl erfolgt durch Tippen auf den Titel (Details weiter unten).

Bilder liegen unter `JPG/<kategorie>/`. Neue JSON-Boxen liegen direkt unter
`boxes/`; bestehende Dateien in dessen Unterordnern bleiben dort.
Der Kategoriename wird kleingeschrieben; eine fehlende Kategorie ergibt `unassigned`.
Beispiel: `Elektronik > Stromversorgung` wird zu `elektronik`.
Bei mehreren Kategorien wird vorläufig die erste verwendet. Eine Box mit mehreren
Datensätzen bleibt vollständig erhalten und verwendet die erste nichtleere Kategorie.

`[ImageSize]` mit `LIMIT=3000` legt die maximale längere Bildkante in Pixeln fest.
Fehlt der Abschnitt, gilt 3000. Kleinere JPGs behalten ihre Bilddaten; beim Speichern
wird lediglich der UserComment aktualisiert. Die bisherige Dateigrößengrenze entfällt.
PNG-Dateien werden als JPG mit derselben Pixelgrenze gespeichert; kleinere Bilder
werden nicht vergrößert. Transparenz wird auf weißem Hintergrund aufgelöst.
Das PNG-Original bleibt erhalten, auch wenn es im konfigurierten Bildordner liegt.
Die Bildvorschau zeigt das vollständige Bild im Seitenverhältnis ohne feste
Höhenbegrenzung; hohe Bilder lassen sich nach unten scrollen.

Beim nächsten App-Start werden lose Bilder direkt in `JPG` nach Kategorie
in Unterordner einsortiert. Bereits in Unterordnern organisierte Bilder bleiben dort. JSON-Dateien werden weder nach Kategorie verschoben noch
umbenannt. Die App liest `boxes` rekursiv, einschließlich aller Unterordner.
Dateien, die eine frühere Version nach `daten` verschoben hat, bleiben an ihrem
Speicherort lesbar und werden nicht erneut verschoben. Die Dateiinhalte und
Änderungszeiten bleiben erhalten. Namenskollisionen bei JPGs überschreiben keine
Dateien; die App meldet stehen gebliebene Quellen. Der Suchindex bleibt getrennt
unter `ChaosBox/data/records.json`. Fehlende mitgelieferte JPG- und
Box-Dateien werden beim ersten Start dieser Version einmalig ergänzt. Bereits
vorhandene Dateien, auch in Unterordnern, werden nicht überschrieben oder verdoppelt.

„JPG öffnen“ zeigt alle JPG/JPEG-Dateien aus dem Bildordner des aktuellen Profils
und sämtlichen Unterordnern, ohne Tiefenbegrenzung. Relative Pfade unterscheiden
gleichnamige Dateien. „Andere Datei …“ öffnet die Android-Dateiauswahl zum Import
weiterer JPG-/PNG-Dateien. Auch Suchtreffer zeigen ihren relativen Quellpfad.
Öffnen, Suche und Upload berücksichtigen sämtliche vorhandenen Unterordner. Bei
gleichnamigen Box-Dateien muss die gewünschte Datei über den JSON-Dateidialog
gewählt werden. Dazu das Feld `Box` leeren und auf „JSON öffnen“ tippen.
Der spätere Upload erhält die vorhandenen Unterordner auch im Server-Zielverzeichnis;
fehlende Ordner einschließlich der Server-Basisordner werden dort angelegt.

Die folgenden älteren Pfadangaben werden durch diese Konfiguration ersetzt.

## Bedienung

Die Startansicht ermöglicht die JPG-/PNG-Auswahl, Box/Kategorie/Kommentar-Eingabe,
EXIF-UserComment-Metadaten und das Speichern. Neue Bilder von außerhalb des JPG-Ordners
werden bei Bedarf auf höchstens 3000 Pixel an der längeren Seite reduziert. Bilder aus
dem konfigurierten JPG-Ordner werden unter demselben Dateinamen atomar
überschrieben, ohne `_cb`-Zusatz. JPGs bis zum eingestellten Pixel-LIMIT werden
nicht neu komprimiert; ihre Bilddaten und andere EXIF-Metadaten bleiben erhalten.
Größere JPGs werden auf die eingestellte längere Bildkante reduziert. Kommentare werden als UTF-8 mit
undefiniertem EXIF-Zeichensatzpräfix gespeichert; ältere ASCII- und Unicode-Kommentare
bleiben lesbar. Vorhandene Metadaten werden beim Öffnen übernommen.

- Bilder: `/storage/emulated/0/ChaosBox/JPG`
- Konfiguration: `/storage/emulated/0/ChaosBox/Setup/setup.ini`
- JSON-Datensätze: `/storage/emulated/0/ChaosBox/boxes`

Die Kategorie-Liste wird aus `Kategorie=` des gewählten `[App.Name]`-Abschnitts geladen.
Ein Wechsel des App-Profils zeigt dessen eigene Kategorien. Fehlt die Datei, wird nach
Erteilung des Speicherzugriffs die mitgelieferte Vorlage angelegt.
Eine bestehende Konfigurationsdatei wird bei der nötigen Umstellung atomar aktualisiert. Gleichnamige neu importierte
Bilder erhalten eine nummerierte Kopie. Android 11+ benötigt Zugriff auf alle Dateien.

„Speichern“ speichert ausschließlich lokal und startet keine Synchronisation.
Der kleine Button „(c) kner“ unter dem Profiltitel startet den Upload manuell für
das gewählte Profil. Nur bereits gespeicherte Dateien werden übertragen.
Ein Dialog zeigt den Fortschritt und bleibt bei Fehlern mit der vollständigen
Meldung geöffnet (Arbeitsschritt, Dateiname/Ziel und Ursache). Nach einem Fehler
kann über denselben Button erneut gestartet werden. Währenddessen ist das Formular
gesperrt. „Abbrechen“ oder Verlassen der App beendet die Übertragung; es gibt
keinen Hintergrunddienst und keinen automatischen Wiederholungsversuch.
Uploads werden zuerst in temporäre Serverdateien geschrieben und erst nach
vollständiger Übertragung umbenannt. Bei Verbindungsabbruch kann eine `.upload`-
Datei auf dem Server zurückbleiben; sie ist keine JPG-/JSON-Quelldatei.
Der Server muss das Umbenennen über bestehende Dateien unterstützen; andernfalls
erscheint ein SFTP-Fehler und die bisherige Serverdatei bleibt erhalten.

„JSON öffnen“ lädt `ChaosBox/boxes/<Box>.json` anhand des Feldes Box (wahlweise
mit `.json`-Endung). Groß-/Kleinschreibung der Eingabe spielt keine Rolle:
`A11` öffnet und speichert `a11.json`; Box-Dateinamen werden kleingeschrieben. Unterstützt werden einzelne Datensätze, Arrays und Objekte
mit mehreren Datensätzen unter eigenen Schlüsseln. Das editierbare Device-Feld
listet die `device`-Werte auf; eine Auswahl lädt Anzahl, Box, Alias, Kategorie,
Kommentar, Package und Erstellungsdatum des Datensatzes. Ein zuvor geöffnetes
Bild wird dabei geschlossen. Der erste Datensatz wird zunächst angezeigt.
Speichern aktualisiert den in der Device-Liste ausgewählten Datensatz in der Box-Datei.
Eine Änderung der Anzahl erzeugt keinen neuen Eintrag, auch bei doppelten Device-Namen.
Auch eine Änderung des Device-Namens bearbeitet den ausgewählten Eintrag. Ohne ausgewählten
Datensatz wird ein bestehendes Device aktualisiert oder ein neues Device angelegt. Zusätzliche JSON-Felder
und das ursprüngliche Erstellungsdatum bleiben erhalten; count/anzahl und pack/package
werden beim Aktualisieren konsistent gehalten.

Beim manuellen Upload bleiben die Unterordner von JPG und boxes erhalten.
Fehlende Server-Zielordner werden einschließlich aller übergeordneten Ordner
automatisch angelegt. Jeder angelegte Ordner erscheint im Uploadprotokoll.
Fehlende Schreibrechte oder andere Serverfehler werden im Dialog angezeigt.
Die Übertragung erfolgt ausschließlich von der APK zum Server und nur für Dateien
innerhalb von `/storage/emulated/0/ChaosBox`. Andere Profilordner wie `Bilderbox`
werden nicht hochgeladen. Es gibt keinen Download, keinen Abgleich zurück zur APK
und keine Übertragung von Löschungen zwischen App und Server.
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
Die mitgelieferte Konfiguration stammt ausschließlich aus
`app/src/main/assets/initial/Setup/setup.ini`. Sie liegt im APK unter
`assets/initial/Setup/setup.ini` und wird beim Öffnen des Editors nach
`/storage/emulated/0/ChaosBox/Setup/setup.ini` kopiert, sofern dort noch keine
Datei vorhanden ist. Eine vorhandene Konfiguration bleibt bei einem App-Update
erhalten. Um die neue Vorlage auf einem bestehenden Gerät zu übernehmen, die
vorhandene Datei zuerst sichern und anschließend ersetzen oder entfernen.
Das ehemalige xfComment-Projekt ist als Quellcode-Archiv unter
`backups/xfcomment-before-integration.tar.gz` gesichert.

---

## Frühere SSH-Copy-Dokumentation

Die folgenden Angaben beschreiben teilweise den früheren Stand; für Startansicht
und Pfade gelten die Angaben oben.

# SSH Copy for Android

Opening the app and saving records never start an upload. Tap the small
“(c) kner” button to upload saved files from the selected profile. The transfer
dialog displays progress and persistent error details. Keep the app open;
leaving it cancels the transfer. Retry explicitly using the same button.
Transfers use SFTP over SSH and preserve newer or unchanged server files.
Only completed temporary uploads are renamed to their final server filenames.

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

The system-wide Android SDK is in `/opt/android-sdk`, and Gradle 8.11.1 is in
`/opt/gradle-8.11.1`. `./build-apk.sh` builds, lints, and copies the result to
`SSHCopy-debug.apk`. `local.properties` points to `/opt/android-sdk`.
User caches and the original debug signing key are in `~/.gradle` and `~/.android`.

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


### App-Profile und Feldüberschriften

Die gemeinsame Datei `ChaosBox/Setup/setup.ini` unterstützt beliebig viele
Abschnitte `[App.Name]`. `[App]` kann mit `Standard=Chaosbox` das anfängliche
Profil festlegen; sonst wird der erste App-Unterabschnitt verwendet.

```ini
[App]
Standard=Chaosbox

[App.Chaosbox]
Titel=Chaosbox
JPG=ChaosBox/JPG
Daten=ChaosBox/boxes
Felder=Box,Anzahl,Device,Alias,Kategorie,Kommentar,Package
Kategorie=Heizung, Wasser, Elektro, Maurer

[App.Bilderbox]
Titel=Bilderbox
JPG=Bilderbox/JPG
Daten=Bilderbox/boxes
Felder=Box,,,Tags,Kategorie,Kommentar
Kategorie=Fotos, Gemälde, Dokumente
```

Ein Tippen auf den Titel öffnet die Profilauswahl. Die Auswahl bleibt über
App-Neustarts erhalten. Ein Wechsel setzt das geöffnete Formular zurück und
verwendet die Bild- und JSON-Ordner des gewählten Profils. Ungespeicherte Eingaben
sollten vorher gespeichert werden. Während eines Uploads ist der Profilwechsel gesperrt. Die konfigurierten Server-Zielpfade bleiben gemeinsam.

`JPG` (alternativ `Bilder`) und `Daten` sind relativ zum gemeinsamen internen
Speicher oder absolut. Profile benötigen getrennte Ordner. Nur JPG-Dateien werden
nach Kategorie in kleingeschriebene Unterordner bzw. `unassigned` einsortiert.
JSON-Dateien bleiben im jeweiligen `boxes`-Ordner oder dessen Unterordnern.
Frühere Standardangaben `Daten=ChaosBox/daten` und `Daten=Bilderbox/daten` werden
auf `boxes` umgestellt; bereits dorthin verschobene Dateien bleiben zusätzlich lesbar.

`Felder` hat sieben feste Positionen: Box, Anzahl, Device, Alias, Kategorie,
Kommentar, Package. Leere Positionen und fehlende Positionen am Ende blenden
Überschrift und Eingabe aus. Im Beispiel heißt Alias nun Tags; Anzahl, Device
und Package sind verborgen. Fehlt die ganze `Felder`-Zeile, sind alle sieben
Standardfelder sichtbar. Die JSON-Schlüssel bleiben unverändert; verborgene
Werte eines geöffneten Datensatzes bleiben erhalten.

Bestehende `[Pfade]`-Dateien werden weiterhin gelesen und beim Öffnen/Speichern
der Setup-Datei in ein `[App.Chaosbox]`-Profil überführt. Eigene Pfade bleiben erhalten.


Der bisherige globale `[Kategorie]`-Abschnitt wird beim Öffnen einer älteren
`setup.ini` entfernt. Seine Einträge werden in jedes Profil ohne eigene
`Kategorie=`-Zeile übernommen. Bereits profilbezogene Kategorien bleiben erhalten.
Eine leere Zeile `Kategorie=` erzeugt nur den Platzhalter „Ohne Kategorie“.


Ist beim Tippen auf „JSON öffnen“ das Feld `Box` leer,
öffnet die App den Android-Dateidialog wie bei der Bildauswahl, möglichst direkt
im `Daten`-Ordner (`boxes`) des aktiven Profils. Ausgewählt werden können
JSON-Dateien innerhalb dieses Ordners und seiner Unterordner. Die ausgewählte Datei
wird am ursprünglichen Speicherort geöffnet und weiterbearbeitet.


## Textbausteine und Zwischenablage

Im gemeinsamen Setup lassen sich beliebig viele benannte Textbausteine ergänzen:

```ini
[TextSnippets]
text1="text1"
text2="text2"
mehrzeilig="Analysiere das Bild.
Zähle die sichtbaren Bauteile.
Gib eine Tabelle aus."
```

„Textbaustein auswählen“ öffnet eine scrollbare Auswahl mit Namen und Inhalt.
Ein Tipp kopiert nur den Inhalt (ohne die äußeren Anführungszeichen) in die
Android-Zwischenablage. Im gewünschten Textfeld lange drücken und „Einfügen“
wählen. Die Auswahl liest die aktuelle Konfiguration; Änderungen im Setup gelten
sofort und für alle App-Profile. Jeder Eintrag beginnt auf einer eigenen Zeile und
hat einen eindeutigen Namen. Ein Wert in doppelten Anführungszeichen darf mehrere
Zeilen umfassen. Das schließende Anführungszeichen steht am Ende der letzten
Textzeile oder auf einer eigenen Zeile (dann endet der Wert mit einem Zeilenumbruch).
Zeilenumbrüche, Leerzeilen, Gleichheitszeichen und Leerzeichen innerhalb des Werts
bleiben erhalten. Auch Zeilen mit `#`, `;` oder `[Abschnitt]` gehören darin zum Text.
Anführungszeichen im Text müssen paarweise auf derselben Zeile stehen, etwa
`Bei Unsicherheit schreibe "?"`. Backslashes bleiben wörtlich erhalten; `\n`
wird nicht umgewandelt. Ein fehlendes Schlusszeichen wird als Setup-Fehler gemeldet.

Bei vorhandenen Installationen wird ein fehlender Abschnitt automatisch mit den
beiden Beispielen ergänzt. Ein vorhandener, auch leerer Abschnitt bleibt erhalten.
