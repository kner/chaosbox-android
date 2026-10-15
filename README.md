# ChaosBox — Android and Ubuntu apps

## Reference and synchronization

The reference desktop application is the sibling `chaosbox-desktop` project.
Android and Desktop now share IDs, revisions, XMP and a transport-independent
change journal. The read-only `chaosbox-kner-at` viewer understands this journal.
See [SYNC-PROTOCOL.md](SYNC-PROTOCOL.md) for configuration, deletion, recovery,
profile mapping and deployment. The common server root is `chaosbox-data`.

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

The media dialog supports selecting multiple files with checkboxes, then **Open**.
**Other file …** also supports multiple JPG/PNG/MP4 files. The first selected file supplies
the preview and initial form values. **Save** writes the same form metadata to
all selected files. Existing JPGs in the configured image folder are updated in
place; imported images are saved as separate JPGs. If saving stops on an error,
the app reports how many files were saved and retains the selection for retry.

Double-tap a JPG/PNG preview to open the image in a separate full-screen view.
The image initially fits the screen while retaining its aspect ratio. Pinch with
two fingers to zoom up to 8× and drag with one finger to move the enlarged image.
Double-tap to zoom to 2.5× around the tapped point, or to reset an enlarged image.
Tap **Close** or use Android Back to return to the unchanged editor form.

When saving an image or JSON record, new **Category** values are appended to
`Kategorie=` in the current profile's section of `setup.ini` and immediately
appear in the dropdown. Empty entries are ignored; existing entries are compared
without regard to case. Commas separate multiple categories, as in the setup.
Other profiles retain their own lists.

Tap Search to enter search mode, enter regular expressions, then tap Search again.
The system Back action cancels search mode and restores the previous form.
Search history is retained during the current activity only.


The app now includes the complete xfComment image editor, JSON viewer,
and the existing SSH upload functionality in the `local.sshcopy` package.

## MP4 videos

The media picker supports JPG/JPEG and MP4 files in the current profile's `JPG`
folder and all its subfolders. **Other file …** imports JPG, PNG, or MP4 files.
Mixed selections share the same form values when saved. A video displays a
thumbnail; the editor does not play videos.

MP4 files store the same JSON fields as JPGs in their ItemList `Comment` (`©cmt`)
metadata: creation and modification dates, Box, Quantity, Device, Alias, Category,
Comment, and Package. Existing ItemList and keyed MP4 comments are read; plain
text comments appear in the Comment field. Existing keyed comments are kept in
sync when saving. Other metadata and encoded video/audio are preserved without
recompression. The image pixel limit does not apply to videos.

Existing MP4 files inside the configured media folder are replaced atomically
at their original location. Imports create a separate `_cb.mp4` file in the
selected category folder, with a numbered suffix on name collisions. Saving
uses a temporary file and requires free storage for a full copy of the video.
MP4 files participate in category-folder organization, the search index, and
manual SFTP uploads under the same profile and destination rules as JPGs.

Editing supports ordinary, non-fragmented MP4 files with either ISO MP4 or
QuickTime-style metadata headers. Fragmented MP4 files,
malformed metadata, movie metadata larger than 32 MiB, and open-ended media boxes
over 4 GiB are rejected without replacing the source. Normal 64-bit MP4 media
boxes are supported. On the first save of a file with metadata before the media,
the updated movie metadata is moved to the end; media offsets remain unchanged.

Run the metadata and interoperability checks with a JDK, FFmpeg, and ExifTool:

```bash
python3 tests/test-mp4.py
```

## Storage paths and category folders

Configure the base folders in `ChaosBox/Setup/setup.ini`:

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

Relative paths refer to Android's shared internal storage; absolute paths are
also supported. Each app profile defines its own image and data paths. Tap the
title to select a profile (see below for details).

Images are stored under `JPG/<category>/`. New JSON boxes are stored directly in
`boxes/`; existing files in its subfolders stay there.
Category names are converted to lowercase; a missing category becomes `unassigned`.
For example, `Elektronik > Stromversorgung` becomes `elektronik`.
For multiple categories, the first is currently used. A box containing multiple
records is preserved in full and uses the first nonempty category.

`[ImageSize]` with `LIMIT=3000` sets the maximum length of the longer image edge
in pixels. If the section is missing, the default is 3000. Smaller JPGs retain
their image data; saving only updates the metadata. The previous file size
limit no longer applies. PNG files are saved as JPGs with the same pixel limit;
smaller images are not enlarged. Transparency is flattened against a white
background. The original PNG is preserved, even inside the configured image
folder. The preview displays the entire image at its original aspect ratio,
without a fixed height limit; tall images can be scrolled vertically.

On the next app launch, images directly in `JPG` are sorted into category
subfolders. Images already organized in subfolders stay there. JSON files are
neither moved by category nor renamed. The app reads `boxes` recursively,
including all subfolders. Files moved to `daten` by an earlier version remain
readable at their existing locations and are not moved again. File contents and
modification times are preserved. JPG filename collisions do not overwrite
files; the app reports source files that could not be moved. The search index
remains separate at `ChaosBox/data/records.json`. Missing bundled JPG and box
files are restored once on the first launch of this version. Existing files,
including those in subfolders, are neither overwritten nor duplicated.

“Open JPG” lists all JPG/JPEG files in the current profile's image folder and
all its subfolders, with no depth limit. Relative paths distinguish files with
the same name. “Other file …” opens the Android file picker to import additional
JPG/PNG files. Search results also display their relative source paths.
Opening, searching, and uploading include all existing subfolders. For box files
with identical names, select the desired file through the JSON file picker:
clear the `Box` field and tap “Open JSON”. Uploads preserve the existing
subfolders in the server destination; missing folders, including the server's
base folders, are created there.

This configuration supersedes the older path descriptions below.

## Usage

The main screen provides JPG/PNG selection, Box/Category/Comment fields,
desktop-compatible XMP metadata, and saving. New images from outside the JPG folder
are reduced to a maximum of 3000 pixels on their longer edge when needed.
Images from the configured JPG folder are overwritten atomically using the
same filename, without a `_cb` suffix. JPGs within the configured pixel LIMIT
are not recompressed; their image data and other EXIF metadata are preserved.
Larger JPGs are reduced to the configured longer-edge length. Image records use
`urn:chaosbox:metadata:1.0/` (`chaosbox:version=1`): box, anzahl, device,
alias, category and package. Comments use `dc:description` with `x-default`;
creation dates use `xmp:CreateDate`, matching chaosbox-desktop. Unrelated XMP is
retained, including when resizing. Legacy EXIF UserComment records remain
readable and are replaced by XMP when saved. MP4 records continue to use JSON
in the standard `©cmt` comment. JSON boxes contain arrays of records with the
same field names and `created`; saves remove the obsolete `modified` field.
Categories are saved in uppercase, as on desktop.

The exchange test checks both directions using the actual desktop reader and
writer, including Unicode, empty fields and unrelated XMP preservation:

```sh
/usr/bin/python3 tests/test-xmp.py --json-jar /path/to/org.json.jar
```

It requires a JDK, ExifTool and the desktop Python dependencies; the reference
project defaults to the sibling `chaosbox-desktop` directory (`--desktop` overrides it).

- Images: `/storage/emulated/0/ChaosBox/JPG`
- Configuration: `/storage/emulated/0/ChaosBox/Setup/setup.ini`
- JSON records: `/storage/emulated/0/ChaosBox/boxes`

The category list is loaded from `Kategorie=` in the selected `[App.Name]`
section. Switching app profiles displays that profile's own categories. If the
file is missing, the bundled template is created after storage access is granted.
An existing configuration file is updated atomically when migration is needed.
Newly imported images with duplicate names receive numbered filenames.
Android 11+ requires all-files access.

“Save” saves locally only and does not start synchronization.
The small “(c) kner” button starts a manual, bidirectional SFTP synchronization
for the selected profile. It receives validated history, records local edits,
merges the current state and publishes immutable changes and media versions.
It also publishes the current media under the server's `JPG/` folder using
their original filenames and category subfolders. Conflicts retain the existing
server copy until resolved; unrelated server files are preserved.
Set `Installation=<Desktop installation ID>` in `[SSH]` or `[SSH.ProfileName]`
in `ChaosBox/Setup/setup.ini` to choose the Desktop installation. Its sync log
shows the ID. Use `RemoteProfile=...` if the collection names differ. Android
refuses the old shared default until an installation is selected; see
`SYNC-PROTOCOL.md` for the isolated server paths.
Progress and errors remain visible; leaving the app cancels the transfer.
Long press the button to resolve conflicts or restore deleted entries. The trash
button deletes the selected entry; long press it to delete a whole JSON box.
Box deletion leaves related media unchanged. No background synchronization runs.

“Open JSON” loads `ChaosBox/boxes/<Box>.json` using the Box field (with an optional
`.json` extension). Input is case-insensitive: `A11` opens and saves `a11.json`;
box filenames are converted to lowercase. Supported formats include individual
records, arrays, and objects containing multiple records under separate keys.
The editable Device field lists the `device` values; selecting one loads the
record's Quantity, Box, Alias, Category, Comment, Package, and creation date.
Any previously opened image is closed. The first record is displayed initially.
Saving updates the record selected in the Device list within the box file.
Changing the quantity does not create a new entry, even with duplicate Device
names. Changing the Device name also edits the selected entry. Without a selected
record, an existing Device is updated or a new Device is created. Additional JSON
fields and the original creation date are preserved; count/anzahl and pack/package
are kept consistent when updating.

Synchronization preserves relative paths. Missing files are not deletions;
use the deletion actions so that tombstones can propagate. Concurrent edits and
deletions are retained as explicit conflicts. Each profile has its own local
journal; non-ChaosBox profiles use a separate default server subtree.

SSH Host, Port, User and DataDestination can be overridden in `[SSH]` or
`[SSH.ProfileName]` in setup.ini. The default main-profile destination is
`chaosbox-data/TXT/.chaosbox-sync`. Private keys and known-hosts files remain in
private app storage. Only ops and blobs are exchanged; local configuration,
identity, trash and indexes are excluded. See the shared protocol document.

## Build and installation

```bash
./build-apk.sh
./upload-adb.sh
```

Output: `SSHCopy-debug.apk` (app name on the phone: ChaosBox).
The bundled configuration comes exclusively from
`app/src/main/assets/initial/Setup/setup.ini`. It is packaged in the APK at
`assets/initial/Setup/setup.ini` and copied to
`/storage/emulated/0/ChaosBox/Setup/setup.ini` when the editor opens, provided
no file already exists there. An existing configuration is preserved during
app updates. To use the new template on an existing device, back up the current
file first, then replace or remove it.
The former xfComment project is backed up as a source archive at
`backups/xfcomment-before-integration.tar.gz`.

---

## Earlier SSH Copy documentation

Some of the following information describes earlier versions; the main screen
and paths are described above.

# SSH Copy for Android

Opening the app and saving records never start an upload. Tap the small
“(c) kner” button to upload saved files from the selected profile. The transfer
dialog displays progress and persistent error details. Keep the app open;
leaving it cancels the transfer. Retry explicitly using the same button.
The current bidirectional protocol is described in SYNC-PROTOCOL.md; older timestamp-based upload rules below are historical.
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
./build-apk.sh
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The delivered APK is a
sideloadable development build; it is not signed for Play Store publication.

Libraries: [JSch](https://github.com/mwiede/jsch) and
[Bouncy Castle](https://www.bouncycastle.org/).
Storage behavior: [Android documentation](https://developer.android.com/training/data-storage/manage-all-files).

The local toolchain is installed in `.tooling/android-sdk` and
`.tooling/gradle-8.11.1` (ignored by Git). `./build-apk.sh` detects these paths,
builds, lints, and copies the result to `SSHCopy-debug.apk`. The local Gradle
cache is `.tooling/gradle-user-home`; `local.properties` points to the local SDK.
`ANDROID_HOME`, `GRADLE_HOME`, and `GRADLE_USER_HOME` can override these defaults.
Without the local toolchain, the script falls back to `/opt/android-sdk` and
`/opt/gradle-8.11.1`. Debug builds use the original development key in
`.tooling/android-user/debug.keystore` when present, matching the existing phone
installation. Keep a secure backup of this ignored file: future updates must
use the same signing key. Without it, Gradle falls back to `~/.android/debug.keystore`,
whose certificate may differ.

File-selection regression check:

```bash
mkdir -p build/test-classes
javac -d build/test-classes app/src/main/java/local/sshcopy/SourceFiles.java tests/SourceFilesTest.java
java -cp build/test-classes local.sshcopy.SourceFilesTest
```

## Debug and Release installation via ADB

```bash
# Build and install Debug
./build-apk.sh
./upload-adb-debug.sh

# Build, sign and install Release
bash ./sign.sh
./upload-adb-release.sh
```

Install a separate Debug app alongside the default app:

```bash
./upload-adb-debug.sh --installdir work SERIAL
```

This builds and installs `SSHCopy-debug-work.apk` as `ChaosBox (work)` with
package ID `local.sshcopy.installation.work`. Repeat the same name to update
that app while preserving its private data. Names start with a lowercase letter
and contain only lowercase letters, digits and underscores (maximum 40 characters).
The device serial is optional. `./build-apk.sh --installdir work` only builds it.
Named installations get an icon color derived from their name; updates keep the
same color. Adaptive launcher icons use this color on the Android home screen
and in the app drawer, including round icons. The default app retains its original
icon colors. Launcher themes may override app colors; disable themed icons to see
the installation colors.
Each app has separate private settings and permissions, but configured shared
storage paths such as `ChaosBox/Setup/setup.ini` and image/data folders remain shared.

`upload-adb.sh` remains a compatibility entry point for Debug.
Without `--installdir`, both upload scripts install the existing APK from the
project folder; they do not build or sign it. Pass a serial number, for example
`./upload-adb-release.sh SERIAL`, to select a device.

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

Use “Edit setup.ini” to edit and save setup.ini directly in the app.
The categories are reloaded afterward.

## Current synchronization destination

The main profile shares `chaosbox-data` with Desktop and the website. The journal
is below `chaosbox-data/TXT/.chaosbox-sync`; materialized images remain under the
local profile's JPG folder. Do not use the historical storage/app/exif targets
for a current shared data set. Connection overrides are described above.

## Shared data file and search

`ChaosBox/data/records.json` contains the records from all JPG/JPEG and MP4 files in
`ChaosBox/JPG` and all JSON files in `ChaosBox/boxes`, including subfolders.
`path` contains the source filename for each record. The file is rebuilt
completely and atomically at app startup, after every save, and before searching.
Invalid sources are reported; the previous data file is preserved.

The magnifying glass switches to empty search fields. Device and Category then
become plain text inputs, and saving is disabled. A second tap starts the search.
Regular expressions match substrings without regard to case; multiple fields
are combined with AND, and empty fields are ignored. Use `^...$` to match an
entire field value. Invalid expressions are flagged on the field. Back cancels
the search. A single result is loaded directly; multiple results are offered
for selection. Normal editing then resumes. Image results open the image;
JSON results display an image with the same Box and Device if one exists,
while saving continues to update a JSON record.

Search fields: Alias and Comment each search Device, Alias, and Comment.
Device searches Device and Comment; Category searches Category and Comment.
Each input needs a match in only one of its target fields; multiple populated
search fields are still combined with AND.

### App profiles and field labels

The shared `ChaosBox/Setup/setup.ini` file supports any number of `[App.Name]`
sections. `[App]` can specify the initial profile using `Standard=Chaosbox`;
otherwise, the first App subsection is used.

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

Tapping the title opens the profile picker. The selection persists across app
restarts. Switching profiles resets the open form and uses the selected profile's
image and JSON folders. Save any unsaved changes first. Profile switching is
disabled during synchronization. Each profile has a separate default server subtree; explicit overrides must identify the same data set on every device.

`JPG` (alternatively `Bilder`) and `Daten` can be relative to shared internal
storage or absolute. Profiles require separate folders. Only JPG files are
sorted by category into lowercase subfolders or `unassigned`.
JSON files remain in the respective `boxes` folder or its subfolders.
Earlier defaults `Daten=ChaosBox/daten` and `Daten=Bilderbox/daten` are migrated
to `boxes`; files already moved to the earlier locations remain readable as well.

`Felder` has seven fixed positions: Box, Quantity, Device, Alias, Category,
Comment, Package. Empty positions and omitted trailing positions hide both
the label and input. In the example, Alias is renamed Tags; Quantity, Device,
and Package are hidden. If the entire `Felder` line is missing, all seven default
fields are visible. JSON keys remain unchanged; hidden values in an opened
record are preserved.

Existing files with a `[Pfade]` section are still read and converted to an
`[App.Chaosbox]` profile when the setup file is opened or saved. Custom paths
are preserved.

The previous global `[Kategorie]` section is removed when opening an older
`setup.ini`. Its entries are copied into every profile without its own
`Kategorie=` line. Existing profile-specific categories are preserved.
An empty `Kategorie=` line produces only the “Uncategorized” placeholder.

If the `Box` field is empty when “Open JSON” is tapped, the app opens the Android
file picker, as it does for image selection, starting in the active profile's
`Daten` folder (`boxes`) where possible. JSON files within that folder and its
subfolders can be selected. The selected file is opened and edited at its
original location.

## Text snippets and clipboard

Any number of named text snippets can be added to the shared setup:

```ini
[TextSnippets]
text1="text1"
text2="text2"
mehrzeilig="Analysiere das Bild.
Zähle die sichtbaren Bauteile.
Gib eine Tabelle aus."
```

“Select text snippet” opens a scrollable list of names and contents.
Tapping an entry copies only its contents (without the enclosing quotation marks)
to the Android clipboard. Long-press the desired text field and choose “Paste”.
The picker reads the current configuration; setup changes take effect immediately
for all app profiles. Each entry starts on its own line and has a unique name.
A double-quoted value can span multiple lines. The closing quotation mark appears
at the end of the final text line or on its own line (in which case the value
ends with a newline). Line breaks, blank lines, equals signs, and spaces within
the value are preserved. Lines containing `#`, `;`, or `[Section]` are also part
of the text within a quoted value. Quotation marks inside the text must be paired
on the same line, for example `If uncertain, write "?"`. Backslashes are preserved
literally; `\n` is not converted. A missing closing quotation mark is reported
as a setup error.

For existing installations, a missing section is automatically added with the
two examples. An existing section, even if empty, is preserved.

## Synchronization tests

After building the APK, supply an installed org.json JAR for JVM tests:

```sh
/usr/bin/python3 tests/test-sync.py --json-jar /path/to/org.json.jar
/usr/bin/python3 tests/test-sftp.py --json-jar /path/to/org.json.jar
```

The second test uses an ephemeral localhost SFTP server and temporary keys;
it never connects to the configured production server.
