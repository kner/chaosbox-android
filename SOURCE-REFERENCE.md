# Android source reference

This reference covers every versioned production source, script, and test source. Android reads the same setup and immutable synchronization journal as the desktop application.

## Application code

| File | Responsibility |
| --- | --- |
| `EditorActivity.java` | Main Android editor, collection switching, capture/import, metadata editing, previews, and manual synchronization UI. |
| `AppProfiles.java` | Parses and normalizes shared `setup.ini` profiles, fields, categories, and text snippets. |
| `AppSettings.java` | Persists selected profile/UI state and safely updates editable setup values. |
| `Config.java` | Defines installation-local paths and packaged setup/credential asset names. |
| `InstallationFiles.java` | Enforces one safe Android storage root per installation and handles setup/generation ownership. |
| `StoragePaths.java` | Resolves profile JPG/TXT folders and validates that paths cannot leave an installation. |
| `SyncDestination.java` | Derives the server JPG/TXT destination from `Installation`, `RemoteProfile`, and `WebInstallation`. |
| `UploadFiles.java` | Runs cancellable SFTP synchronization and reports user-facing failures. |
| `SyncSftp.java` | Exchanges immutable journal operations/blobs over SFTP and publishes manifests, media, and JSON projections. |
| `SyncStore.java` | Android implementation of journal capture, merging, media references, conflicts, and atomic writes. |
| `BoxRecords.java`, `DataIndex.java` | Read/write box JSON records and build searchable local indexes. |
| `CategoryValues.java`, `InputValues.java` | Validate and normalize category and form values. |
| `SourceFiles.java`, `RemoteFiles.java`, `LocalData.java` | Safely enumerate local inputs, remote directories, and bounded text data. |
| `JpegComments.java`, `XmpRecords.java`, `Mp4Comments.java` | Read/write JPEG comments, XMP metadata, and MP4 metadata without re-encoding media streams. |
| `ImageZoom.java`, `ZoomImageView.java` | Gesture-aware image zooming and rendering. |
| `TextSnippets.java` | Parses quoted multiline text snippets without treating their content as INI sections. |
| `UploadErrors.java` | Converts transport errors into actionable Android messages. |

## Build and operational scripts

| File | Responsibility |
| --- | --- |
| `build.gradle`, `settings.gradle`, `app/build.gradle` | Gradle project, module, dependencies, signing/build version, and generated installation-name configuration. |
| `build-apk.sh` | Builds a named debug APK. |
| `sign.sh`, `sign1.sh` | Produce signed release APKs from the shared product version. |
| `configure-adb-sync.py` | Updates an Android setup file over ADB with an explicit Desktop installation target. |
| `upload-adb.sh`, `upload-adb-debug.sh`, `upload-adb-release.sh`, `upload-adb-CBHaushalt.sh` | Build/install helpers for ADB targets and the Haushalt profile. |
| `exiftool.sh` | Compatibility wrapper for metadata tooling. |
| `scripts/server-delete.py` | Server-side journal-aware deletion helper. |

## Test sources

The Java tests named `*Test.java` exercise the production class with the same stem: profiles, records, category/input validation, indexing, storage, installation reset, JPEG/MP4/XMP handling, local/remote files, journal operations, destinations, SFTP, snippets, and upload errors. `MediaFilenameConflictTest.java`, `RemoteDirectoriesTest.java`, and `SharedSetupTest.java` cover the corresponding cross-cutting edge cases.

The Python tests provide end-to-end compatibility checks: `test-sync.py` compares the Android journal with desktop behavior; `test-sftp.py` verifies SFTP and web visibility; `test-upload-adb.py` tests ADB setup editing; `test-mp4.py`, `test-xmp.py`, and `test-server-delete.py` validate metadata and maintenance tools.

Run the documented validation commands from `SYNC-VALIDATION.md`.
