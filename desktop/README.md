# ChaosBox Desktop for Ubuntu 24.04

A native Python/Tk application using the same metadata fields, JSON records and
setup profile format as the Android app. No browser or Android emulator is needed.
The interface is in English; category names, custom labels and snippets retain
their configured language.

## Install and launch

On Ubuntu 24.04 the dependencies are available from Ubuntu packages:

```bash
sudo apt install python3-tk python3-pil python3-pil.imagetk python3-paramiko libimage-exiftool-perl ffmpeg xclip
```

From the repository root:

```bash
./run-desktop.sh --check
./run-desktop.sh
```

To install for the current user, run:

```bash
./install-desktop.sh
```

Launch **ChaosBox Desktop** from the Ubuntu application menu. The installer copies
the runtime to `~/.local/share/chaosbox` and creates
`~/.local/share/applications/chaosbox.desktop`. Run the installer again after
updating the source; existing setup and data are retained. No administrator
privileges are needed for this installation when dependencies are already present.

## Debian package (Ubuntu 24.04)

Build an architecture-independent installer using the system Python and dpkg:

```bash
cd desktop
/usr/bin/python3 build_deb.py
sudo apt install ./dist/chaosbox-desktop_1.0.2_all.deb
```

Launch **ChaosBox Desktop** from the application menu or run `/usr/bin/chaosbox`.
The package installs to `/usr/share/chaosbox-desktop`; apt installs its runtime
dependencies. It includes the current full-screen and file-operation fixes.
Settings and media remain in the user's home directory and survive removal with
`sudo apt remove chaosbox-desktop`. SSH credentials are not included.

An earlier per-user installation can leave a second menu entry. The old entry
uses `~/.local/share/chaosbox`; `/usr/bin/chaosbox` always starts the Debian build.
To use only the Debian menu entry, remove the old
`~/.local/share/applications/chaosbox.desktop` launcher.

For subsequent releases use `build_deb.py --version VERSION`. The `dist` folder
contains the `.deb` and a SHA-256 checksum file.

## Data and settings

Default locations:

| Content | Location |
| --- | --- |
| Chaosbox images and videos | `~/ChaosBox/JPG` |
| Chaosbox JSON records | `~/ChaosBox/boxes` |
| Bilderbox images and records | `~/Bilderbox/JPG` and `~/Bilderbox/boxes` |
| Setup | `~/ChaosBox/Setup/setup.ini` |
| Local SSH credentials | `~/.config/chaosbox/credentials` |

The **Setup** button edits the configuration. Profile paths are relative to the
user's home directory unless absolute. Each profile has its own categories and
search index. New comma-separated Category values are appended to the active
profile when saving. Existing categories are compared without regard to case.
Loose media in the profile's image directory is organized into category folders
on initialization; duplicate names are retained without overwriting other files.

For an independent test data directory:

```bash
./run-desktop.sh --data-root /tmp/chaosbox-demo --state-dir /tmp/chaosbox-demo/config
```

`--data-root` replaces the home-directory base; for example the default Chaosbox
profile then uses `/tmp/chaosbox-demo/ChaosBox/JPG`.

## Editing

- **Open JPG / MP4** shows a scrollable thumbnail grid with **1–10 columns**.
  Only files directly in the current folder are shown. **Choose folder …** changes
  folders. The last selected folder is remembered per profile across restarts,
  including folders used through **Other files …**.
  Double-clicking a folder in the folder picker selects it and opens its images
  immediately. Enter a folder path and press Enter to navigate directly.
  Click tiles to toggle multiple selections, Shift-click to select a range, or
  use **Select all** / **Clear**. The selection survives column changes.
  Previews load in the background; unavailable previews remain selectable.
  Thumbnail size follows the column width while preserving image proportions.
  **Show UserComment** displays **box | category | comment** below each thumbnail,
  limited to 60 characters in total. Comments load in the background when enabled.
  **Other files …**
  also imports PNG files. The first file supplies initial values and preview.
  **Save** writes the same form values to every selected file.
  Local saves refresh only the search index entries of the saved files.
  Startup and searches still rebuild the full index to discover external changes;
  a missing or damaged desktop index cache also triggers a full rebuild.
- Saved media goes into the category folder, or **unassigned** when no category
  is specified. Existing profile media moves there when its category changes;
  media already in the correct folder is updated in place. Imports receive a separate `_cb`
  filename; name collisions get numbered suffixes. PNG files become JPG with a
  white background. Images exceeding `[ImageSize] LIMIT` are resized. Smaller
  JPGs retain their encoded image data; MP4 video/audio is not re-encoded.
- JPG metadata uses EXIF `UserComment`. MP4 metadata uses ItemList `Comment`;
  existing Keys comments are synchronized. JSON strings use the Android field
  names, including `anzahl` and `package`.
- **Open JSON** uses the Box field if supplied, or opens a file chooser. Select a
  record through Device; profiles with hidden Device use a record chooser.
  Saving a selected record preserves unknown fields and its creation date.
- **Search** first enters search mode; pressing it again runs the query. Queries
  use case-insensitive Python regular expressions. Nonempty fields are combined
  with AND. Category and Device also search Comment; Comment and Alias search
  Device, Alias and Comment. Python-specific regex syntax can differ from Java.
  **Cancel search** restores the form; **Repeat search** reuses the last query.
- Double-click an image preview for full-screen viewing. Zoom using the mouse
  wheel or +/−, drag to pan, double-click for 2.5×/reset, and press Escape to close.
  Zoom reaches 8×; full-screen images are loaded up to 12000 pixels per side.
  **COPY** copies the displayed image as PNG to the clipboard for pasting with
  Ctrl+V (requires `xclip`; also works in Ubuntu's XWayland session).
  MP4 files show a still preview, without an embedded video player.
- **TXT** opens configured text snippets and copies the selection to the clipboard.
- Keyboard shortcuts: Ctrl+O opens media, Ctrl+S saves, Ctrl+F enters/runs search.

## Manual SSH upload

**Upload saved files** sends saved data; it never runs automatically. Host, port,
user, destinations, key file and known-hosts file are configured in `[SSH]`.
The local installer reuses the project's existing Android key and known-hosts
files when available, placing private copies in the credentials directory.
Existing credential files are retained. Keys are never included in desktop source
packages. Connections require a known host key and public-key authentication.

As with Android, only profile paths inside `ChaosBox` are eligible for upload;
the default Bilderbox profile is excluded. Relative subdirectories are retained.
Files are sent when missing remotely or locally newer, using a temporary remote
file and atomic rename. Remote extra files are not deleted. Cancel or close the
upload dialog to stop. The server must support OpenSSH's POSIX rename extension.

## Verification

```bash
/usr/bin/python3 -m unittest discover -s desktop/tests -v
/usr/bin/python3 desktop/tests/tk_smoke.py
```

The second command requires a graphical session. Tests use temporary data and
cover metadata round-trips, encoded image/video preservation, JSON edits,
category persistence, search, installation, UI workflows and simulated SFTP.
They do not connect to the live SSH server.
