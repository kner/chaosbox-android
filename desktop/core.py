"""Desktop storage and metadata operations, compatible with ChaosBox Android."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import errno
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import uuid

from PIL import Image, ImageOps

FIELDS = ("box", "anzahl", "device", "alias", "category", "comment", "package")
LABELS = ("Box", "Quantity", "Device", "Alias", "Category", "Comment", "Package")
MEDIA = {".jpg", ".jpeg", ".mp4"}
IMPORTS = MEDIA | {".png"}
RECORD_KEYS = set(FIELDS) | {"count", "pack", "created", "modified"}
REPO = Path(__file__).resolve().parent.parent
DEFAULT_SETUP = Path(__file__).with_name("setup.ini")


class Cancelled(Exception):
    pass


class BatchError(Exception):
    def __init__(self, message, selection, completed):
        super().__init__(message)
        self.selection, self.completed = selection, completed


def logical_lines(text):
    """Keep Android's quoted multiline TextSnippets intact (including section-like text)."""
    active, pending = False, None
    for raw in text.lstrip("\ufeff").splitlines():
        line = raw.strip()
        if pending is not None:
            pending += "\n" + raw
            if raw.count('"') % 2:
                if not line.endswith('"'):
                    raise ValueError("A text snippet's closing quote must end its line.")
                yield pending
                pending = None
            continue
        if line.startswith("[") and line.endswith("]"):
            active = line[1:-1].strip().casefold() == "textsnippets"
        elif active and line and not line.startswith(("#", ";")) and "=" in line:
            value = line.split("=", 1)[1].strip()
            if value.startswith('"') and value.count('"') % 2:
                pending = raw
                continue
        yield raw
    if pending is not None:
        raise ValueError("A text snippet is missing its closing quote.")


def sections(text):
    result, current, section_name = {}, None, ""
    for raw in logical_lines(text):
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            section_name = line[1:-1].strip()
            current = result.setdefault(section_name, {})
        elif current is not None and line and not line.startswith(("#", ";")):
            if "=" in line:
                key, value = line.split("=", 1)
                current[key.strip() if section_name.casefold() == "textsnippets" else key.strip().casefold()] = value.strip()
    return result


def unique_categories(text):
    seen, result = set(), []
    for item in text.split(","):
        item = item.strip()
        if item and item.casefold() not in seen:
            seen.add(item.casefold())
            result.append(item)
    return result


def within(path, parent):
    return path.resolve().is_relative_to(parent.resolve())


def atomic_write(path, content, newer=False, mode=None):
    path = Path(path)
    if path.is_symlink():
        raise ValueError(f"Refusing to replace a symbolic link: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    previous = path.stat() if path.exists() else None
    fd, temporary = tempfile.mkstemp(prefix=".chaosbox-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(content if isinstance(content, bytes) else content.encode("utf-8"))
            out.flush()
            os.fsync(out.fileno())
        os.chmod(temporary, mode if mode is not None else stat.S_IMODE(previous.st_mode) if previous else 0o600)
        if newer and previous:
            modified = max(time.time(), int(previous.st_mtime) + 1)
            os.utime(temporary, (modified, modified))
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


@dataclass
class Profile:
    id: str
    title: str
    images: Path
    data: Path
    legacy_data: Path
    index: Path
    labels: list[str]
    categories: list[str]


class Settings:
    def __init__(self, root, state_dir, setup=None):
        self.root = Path(root).expanduser().resolve()
        self.state_dir = Path(state_dir).expanduser().resolve()
        self.path = Path(setup).expanduser().resolve() if setup else self.root / "ChaosBox/Setup/setup.ini"
        self.profiles = []
        self.limit = 3000
        self.snippets = {}
        self.ssh = {}

    def ensure(self):
        if not self.path.exists():
            text = DEFAULT_SETUP.read_text(encoding="utf-8")
            text += ("\n[SSH]\nHost=access983197478.webspace-data.io\nPort=22\nUser=u114229695\n"
                     "ImageDestination=l1/storage/app/exif/jpg\nDataDestination=l1/storage/app/exif/data\n"
                     f"KeyFile={self.state_dir / 'credentials/android_copy'}\n"
                     f"KnownHosts={self.state_dir / 'credentials/known_hosts'}\n")
            atomic_write(self.path, text)
        self.reload()

    def parse(self, text):
        groups = sections(text)
        lookup = {key.casefold(): value for key, value in groups.items()}
        limit = int(lookup.get("imagesize", {}).get("limit", "3000"))
        if not 1 <= limit <= 20000:
            raise ValueError("ImageSize LIMIT must be between 1 and 20000.")
        profiles = []
        legacy_categories = []
        active = False
        for raw in logical_lines(text):
            line = raw.strip()
            if line.startswith("[") and line.endswith("]"):
                active = line.casefold() == "[kategorie]"
            elif active and line and not line.startswith(("#", ";")):
                legacy_categories.extend(unique_categories(line.split("=", 1)[-1]))
        def resolve(value):
            path = Path(value).expanduser()
            return (path if path.is_absolute() else self.root / path).resolve()
        profile_groups = [(name[4:].strip(), values) for name, values in groups.items()
                          if name.lower().startswith("app.")]
        if not profile_groups:
            old = lookup.get("pfade", {})
            profile_groups = [("Chaosbox", {"jpg": old.get("bilder", "ChaosBox/JPG"),
                                            "daten": old.get("daten", "ChaosBox/boxes")})]
        roots, ids = [], set()
        for name, values in profile_groups:
            if not name or name.casefold() in ids:
                raise ValueError("Profile names must be nonempty and unique.")
            ids.add(name.casefold())
            image_path = values.get("bilder", values.get("jpg", ""))
            data_path = values.get("daten", "")
            if not image_path or not data_path:
                raise ValueError(f"{name}: JPG and Daten are required.")
            images, data = resolve(image_path), resolve(data_path)
            legacy_data = data
            base = "ChaosBox" if name.casefold() in ("chaosbox", "chaobox") else "Bilderbox" if name.casefold() == "bilderbox" else None
            if base and images == self.root / base / "JPG" and data in (self.root / base / "daten", self.root / base / "boxes"):
                data = self.root / base / "boxes"
                legacy_data = self.root / base / "daten"
            labels = values.get("felder", ",".join(LABELS)).split(",")
            if len(labels) > 7:
                raise ValueError(f"{name}: Felder has more than seven positions.")
            translated = {"anzahl": "Quantity", "kategorie": "Category", "kommentar": "Comment"}
            labels = [translated.get(label.strip().casefold(), label.strip()) for label in labels]
            labels += [""] * (7 - len(labels))
            key = str(uuid.UUID(bytes=hashlib.md5(name.encode()).digest(), version=3))
            index = self.root / "ChaosBox" / ("data" if base == "ChaosBox" else f".indices/{key}")
            reserved = [self.root / "ChaosBox/Setup", self.root / "ChaosBox/data", self.root / "ChaosBox/.indices"]
            for path in dict.fromkeys([images, data, legacy_data]):
                if any(within(path, other) or within(other, path) for other in roots + reserved):
                    raise ValueError(f"Overlapping profile or internal folders: {path}")
                roots.append(path)
            profiles.append(Profile(name, values.get("titel", name), images, data, legacy_data, index,
                                    labels, unique_categories(values.get("kategorie", ",".join(legacy_categories)))))
        default = lookup.get("app", {}).get("standard", profiles[0].id)
        if default.casefold() not in ids:
            raise ValueError(f"Unknown default profile: {default}")
        snippets = {}
        for name, value in lookup.get("textsnippets", {}).items():
            snippets[name] = value[1:-1] if value.startswith('"') and value.endswith('"') else value
        ssh = dict(lookup.get("ssh", {}))
        ssh.setdefault("host", "access983197478.webspace-data.io")
        ssh.setdefault("port", "22")
        ssh.setdefault("user", "u114229695")
        ssh.setdefault("imagedestination", "l1/storage/app/exif/jpg")
        ssh.setdefault("datadestination", "l1/storage/app/exif/data")
        ssh.setdefault("keyfile", str(self.state_dir / "credentials/android_copy"))
        ssh.setdefault("knownhosts", str(self.state_dir / "credentials/known_hosts"))
        if not 1 <= int(ssh["port"]) <= 65535:
            raise ValueError("SSH port must be between 1 and 65535.")
        return profiles, default, limit, snippets, ssh

    def reload(self):
        self.profiles, self.default, self.limit, self.snippets, self.ssh = self.parse(self.path.read_text(encoding="utf-8-sig"))

    def save_text(self, text):
        self.parse(text)
        atomic_write(self.path, text)
        self.reload()

    def profile(self, name):
        return next((p for p in self.profiles if p.id.casefold() == name.casefold()),
                    next(p for p in self.profiles if p.id.casefold() == self.default.casefold()))

    def remember_category(self, profile, entered):
        if "\n" in entered or "\r" in entered:
            raise ValueError("Category must be a single line.")
        self.reload()
        current = self.profile(profile.id)
        known = {item.casefold() for item in current.categories}
        additions = [item for item in unique_categories(entered) if item.casefold() not in known]
        if not additions:
            return
        text = self.path.read_text(encoding="utf-8")
        lines = list(logical_lines(text))
        active, found, key, end = False, False, None, len(lines)
        for i, raw in enumerate(lines):
            line = raw.strip()
            if line.startswith("[") and line.endswith("]"):
                if active:
                    end = i
                active = line[1:-1].casefold() == f"app.{current.id}".casefold()
                if active:
                    found, end = True, len(lines)
            elif active and "=" in line and line.split("=", 1)[0].strip().casefold() == "kategorie":
                key = i
        if key is not None:
            prefix, value = lines[key].split("=", 1)
            lines[key] = prefix + "=" + ", ".join([value.strip()] if value.strip() else [] ) + (", " if value.strip() else "") + ", ".join(additions)
        elif found:
            lines.insert(end, "Kategorie=" + ", ".join(current.categories + additions))
        else:
            # Materialize the legacy profile without altering unrelated sections.
            lines.extend([f"\n[App.{current.id}]", f"Titel={current.title}", f"JPG={current.images}",
                          f"Daten={current.data}", "Felder=" + ",".join(current.labels),
                          "Kategorie=" + ", ".join(current.categories + additions)])
        self.save_text("\n".join(lines).rstrip() + "\n")


def files(folder, extensions):
    folder = Path(folder)
    if not folder.exists():
        return []
    result = []
    for base, directories, names in os.walk(folder, followlinks=False):
        directories[:] = sorted(d for d in directories if not (Path(base) / d).is_symlink())
        for name in sorted(names):
            path = Path(base) / name
            if path.suffix.lower() in extensions and not path.is_symlink() and path.is_file():
                result.append(path)
    return sorted(result)


def category_folder(value):
    first = re.split(r"[,;|\r\n]", value or "")[0].split(">", 1)[0].strip().lower()
    if not first or first in ("ohne kategorie", ".", ".."):
        return "unassigned"
    return re.sub(r"[/\\\x00-\x1f\x7f]", "_", first)


def normalized(record):
    data = dict(record)
    data["anzahl"] = max(0, int(data.get("count", data.get("anzahl", data.get("Anzahl", 0))) or 0))
    data["package"] = data.get("pack", data.get("package", ""))
    data["comment"] = data.get("comment", data.get("kommentar", data.get("Kommentar", "")))
    category = next((v for k, v in data.items() if k.casefold() in ("category", "kategorie")), "")
    if isinstance(category, list):
        category = ", ".join(str(v) for v in category)
    data["category"] = str(category or "")
    for field in FIELDS:
        data.setdefault(field, "")
    return data


def parse_metadata(raw):
    if not raw.strip():
        return normalized({})
    try:
        data = json.loads(raw)
        if isinstance(data, list):
            records = [normalized(item) for item in data if isinstance(item, dict)]
            if not records:
                return normalized({})
            first = records[0]
            first["comment"] = "\n\n".join(str(item["comment"]) for item in records if item["comment"])
            first["category"] = ", ".join(dict.fromkeys(str(item["category"]) for item in records if item["category"]))
            return first
        if not isinstance(data, dict):
            raise ValueError("Not an object")
        return normalized(data)
    except (ValueError, TypeError):
        return normalized({"comment": raw})


def run_tool(*arguments, timeout=300):
    result = subprocess.run(arguments, capture_output=True, timeout=timeout)
    if result.returncode:
        error = result.stderr.decode("utf-8", "replace").strip()
        raise ValueError(error or f"{arguments[0]} failed ({result.returncode}).")
    return result.stdout


def read_metadata(path):
    path = Path(path).resolve()
    if path.suffix.lower() == ".mp4":
        tags = json.loads(run_tool("exiftool", "-j", "-G1", "-ItemList:Comment", "-Keys:Comment", "-UserData:Comment", str(path)))
        item = tags[0]
        raw = next((item[key] for key in ("ItemList:Comment", "Keys:Comment", "UserData:Comment") if key in item), "")
    else:
        raw = run_tool("exiftool", "-s3", "-EXIF:UserComment", str(path)).decode("utf-8").rstrip("\r\n")
    return parse_metadata(raw)


def validate_record(values):
    data = dict(values)
    try:
        quantity = str(data.get("anzahl", "")).strip() or "0"
        if not re.fullmatch(r"[0-9]+", quantity) or int(quantity) > 2147483647:
            raise ValueError()
        data["anzahl"] = int(quantity)
    except (ValueError, TypeError):
        raise ValueError("Quantity must be an integer between 0 and 2147483647.")
    now = datetime.now().astimezone().isoformat(timespec="seconds")
    data["created"] = data.get("created") or now
    data["modified"] = now
    return data


def reserve_output(directory, name):
    directory.mkdir(parents=True, exist_ok=True)
    stem, extension = Path(name).stem, Path(name).suffix
    suffix = 0
    while True:
        path = directory / (f"{stem}_{suffix}{extension}" if suffix else name)
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(fd)
            return path
        except FileExistsError:
            suffix += 1


def save_media(source, profile, metadata, limit):
    source = Path(source)
    if source.is_symlink() or not source.is_file() or source.suffix.lower() not in IMPORTS:
        raise ValueError(f"Unsupported or unavailable media file: {source}")
    video = source.suffix.lower() == ".mp4"
    original = source.resolve()
    editing = within(original, profile.images) and source.suffix.lower() in MEDIA
    destination = original.parent if editing else profile.images / category_folder(metadata.get("category", ""))
    if not editing and (destination.is_symlink() or destination.resolve().parent != profile.images.resolve()):
        raise ValueError("Invalid category folder.")
    destination.mkdir(parents=True, exist_ok=True)
    extension = ".mp4" if video else ".jpg"
    target, reserved = original if editing else None, False
    fd, temp_name = tempfile.mkstemp(prefix=".save-", suffix=extension, dir=destination)
    os.close(fd)
    temporary = Path(temp_name)
    try:
        resized = False
        if video:
            shutil.copyfile(original, temporary)
        else:
            with Image.open(original) as image:
                if image.format not in ("JPEG", "PNG"):
                    raise ValueError("Select a JPG, PNG or MP4 file.")
                resized = image.format == "PNG" or max(image.size) > limit
                if resized:
                    converted = ImageOps.exif_transpose(image).convert("RGBA")
                    converted.thumbnail((limit, limit), Image.Resampling.LANCZOS)
                    rgb = Image.new("RGB", converted.size, "white")
                    rgb.paste(converted, mask=converted.getchannel("A"))
                    rgb.save(temporary, "JPEG", quality=92)
                    width, height = rgb.size
                else:
                    shutil.copyfile(original, temporary)
        payload = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix="chaosbox-comment-", delete=False) as text:
            text.write(payload)
            comment_file = Path(text.name)
        try:
            args = ["exiftool", "-overwrite_original"]
            if resized:
                args += ["-TagsFromFile", str(original), "-all:all", "-Orientation#=1",
                         f"-ExifImageWidth={width}", f"-ExifImageHeight={height}", "-ThumbnailImage="]
            args += [f"-ItemList:Comment<={comment_file}"] if video else [f"-EXIF:UserComment<={comment_file}"]
            if video:
                old = json.loads(run_tool("exiftool", "-j", "-G1", "-Keys:Comment", str(original)))[0]
                if "Keys:Comment" in old:
                    args += [f"-Keys:Comment<={comment_file}"]
            run_tool(*args, str(temporary))
        finally:
            comment_file.unlink(missing_ok=True)
        # Validate the newly written metadata before replacing any user file.
        result = read_metadata(temporary)
        if any(str(result.get(field, "")) != str(metadata.get(field, "")) for field in FIELDS):
            raise ValueError("Metadata verification failed; the source file was not changed.")
        if target is None:
            target = reserve_output(destination, original.stem + "_cb" + extension)
            reserved = True
        old_time = target.stat().st_mtime
        modified = max(time.time(), int(old_time) + 1)
        os.utime(temporary, (modified, modified))
        if editing:
            os.chmod(temporary, stat.S_IMODE(original.stat().st_mode))
        with temporary.open("rb") as saved:
            os.fsync(saved.fileno())
        os.replace(temporary, target)
        reserved = False
        return target
    finally:
        temporary.unlink(missing_ok=True)
        if reserved:
            target.unlink(missing_ok=True)


def save_batch(paths, profile, record, limit, progress=lambda _: None):
    selection = list(paths)
    for index, source in enumerate(selection):
        try:
            progress(f"Saving {index + 1}/{len(selection)}: {source.name}")
            selection[index] = save_media(source, profile, record, limit)
        except Exception as error:
            raise BatchError(f"Saved {index} of {len(selection)} files. {source.name}: {error}", selection, index) from error
    return selection


def box_filename(name):
    name = name.strip().lower()
    if not name or re.search(r"[/\\\x00-\x1f\x7f]", name):
        raise ValueError("Box must be a valid filename without slashes or control characters.")
    return name if name.endswith(".json") else name + ".json"


def records_from(value):
    if isinstance(value, list):
        result = value
    elif isinstance(value, dict):
        result = [value] if RECORD_KEYS.intersection(value) else list(value.values())
    else:
        raise ValueError("Expected a JSON object or array.")
    if any(not isinstance(item, dict) for item in result):
        raise ValueError("JSON records must be objects.")
    return result


def load_box(path):
    return records_from(json.loads(Path(path).read_text(encoding="utf-8-sig")))


def save_box(path, record, selected=None):
    path = Path(path)
    if path.is_symlink():
        raise ValueError("Box file must not be a symbolic link.")
    records = load_box(path) if path.exists() else []
    if selected is not None and not 0 <= selected < len(records):
        raise ValueError("The selected record no longer exists. Reopen the box.")
    if selected is None:
        selected = next((i for i, item in enumerate(records) if item.get("device", "") == record.get("device", "")), None)
    if selected is None:
        records.append(dict(record))
        selected = len(records) - 1
    else:
        item = records[selected]
        created = item.get("created")
        item.update(record)
        if created:
            item["created"] = created
        if "count" in item:
            item["count"] = record["anzahl"]
        if "pack" in item:
            item["pack"] = record.get("package", "")
    atomic_write(path, json.dumps(records, ensure_ascii=False, indent=2) + "\n", newer=True)
    return records, selected


@dataclass
class Entry:
    source: Path
    data: dict
    media: bool
    index: int | None = None


def json_files(profile):
    return sorted(set(files(profile.data, {".json"}) + files(profile.legacy_data, {".json"})))


def build_index(profile, progress=lambda _: None):
    entries = []
    for path in files(profile.images, MEDIA):
        progress(f"Reading {path.name}")
        try:
            entries.append(Entry(path, read_metadata(path), True))
        except Exception as error:
            raise ValueError(f"{path}: {error}") from error
    for path in json_files(profile):
        try:
            for index, raw in enumerate(load_box(path)):
                data = normalized(raw)
                data["box"] = data["box"] or path.stem
                entries.append(Entry(path, data, False, index))
        except Exception as error:
            raise ValueError(f"{path}: {error}") from error
    serialized = [dict(entry.data, path=entry.source.name) for entry in entries]
    atomic_write(profile.index / "records.json", json.dumps(serialized, ensure_ascii=False, indent=2) + "\n")
    return entries


def compile_query(values):
    result = {}
    for key, value in values.items():
        if value:
            try:
                result[key] = re.compile(value, re.IGNORECASE)
            except re.error as error:
                raise ValueError(f"{LABELS[FIELDS.index(key)]}: invalid regular expression: {error}") from error
    return result


def search(entries, patterns):
    def match(entry):
        for field, pattern in patterns.items():
            targets = ("device", "alias", "comment") if field in ("alias", "comment") else (field, "comment") if field in ("category", "device") else (field,)
            if not any(pattern.search(str(entry.data.get(key, ""))) for key in targets):
                return False
        return True
    return [entry for entry in entries if match(entry)]


def related_media(hit, entries):
    if hit.media:
        return hit.source
    return next((entry.source for entry in entries if entry.media and hit.data.get("box")
                 and str(entry.data.get("box", "")).casefold() == str(hit.data["box"]).casefold()
                 and entry.data.get("device", "") == hit.data.get("device", "")), None)


def migrate_media(profile, progress=lambda _: None):
    warnings = []
    for source in files(profile.images, MEDIA):
        if source.parent != profile.images:
            continue
        try:
            progress(f"Organizing {source.name}")
            directory = profile.images / category_folder(read_metadata(source).get("category", ""))
            if directory.is_symlink():
                raise ValueError("Category directory is a symbolic link.")
            directory.mkdir(parents=True, exist_ok=True)
            target = directory / source.name
            # An exclusive hard link makes collisions safe; no existing file is overwritten.
            os.link(source, target)
            source.unlink()
        except Exception as error:
            warnings.append(f"{source.name}: {error}")
    return warnings


def load_preview(path, maximum=(1600, 1200)):
    path = Path(path)
    if path.suffix.lower() == ".mp4":
        import io
        image = Image.open(io.BytesIO(run_tool("ffmpeg", "-v", "error", "-i", str(path), "-frames:v", "1",
                                               "-vf", "scale=1280:1280:force_original_aspect_ratio=decrease",
                                               "-f", "image2pipe", "-vcodec", "png", "-", timeout=60)))
    else:
        image = Image.open(path)
    with image:
        result = ImageOps.exif_transpose(image).convert("RGB")
        result.thumbnail(maximum, Image.Resampling.LANCZOS)
        return result.copy()


def upload(settings, profile, cancel: threading.Event, progress=lambda _: None):
    import paramiko
    def check():
        if cancel.is_set():
            raise Cancelled("Upload cancelled. Local files are retained.")
    ssh = settings.ssh
    key, hosts = Path(ssh["keyfile"]).expanduser(), Path(ssh["knownhosts"]).expanduser()
    if not key.is_file() or not hosts.is_file():
        raise ValueError("SSH key or known_hosts is missing. Configure KeyFile and KnownHosts in Setup → [SSH].")
    upload_root = settings.root / "ChaosBox"
    pending, destinations = [], set()
    for root, candidates, remote_root in (
            (profile.images, files(profile.images, MEDIA), ssh["imagedestination"]),
            (profile.data, json_files(profile), ssh["datadestination"])):
        for file in candidates:
            if not within(file, upload_root):
                continue
            relative_root = root if within(file, root) else profile.legacy_data
            remote = posixpath.join(remote_root, file.relative_to(relative_root).as_posix())
            if remote in destinations:
                raise ValueError(f"Duplicate upload destination: {remote}")
            destinations.add(remote)
            pending.append((file, remote))
    if not pending:
        progress("No eligible files to upload. Only files below ~/ChaosBox are uploaded.")
        return
    client = paramiko.SSHClient()
    client.load_host_keys(str(hosts))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    stop_watcher = threading.Event()
    def watch_cancel():
        while not stop_watcher.wait(.2):
            if cancel.is_set():
                client.close()
                return
    watcher = threading.Thread(target=watch_cancel, daemon=True)
    watcher.start()
    stage, copied, skipped = "Connect", 0, 0
    try:
        check()
        progress(f"Connecting to {ssh['host']} …")
        client.connect(ssh["host"], port=int(ssh["port"]), username=ssh["user"], key_filename=str(key),
                       allow_agent=False, look_for_keys=False, timeout=15, auth_timeout=15, banner_timeout=15)
        check()
        sftp = client.open_sftp()
        sftp.get_channel().settimeout(20)
        directories = set()
        def mkdirs(directory):
            if directory in ("", ".", "/") or directory in directories:
                return
            check()
            try:
                item = sftp.stat(directory)
                if not stat.S_ISDIR(item.st_mode):
                    raise ValueError(f"Not a server directory: {directory}")
            except OSError as error:
                if error.errno != errno.ENOENT:
                    raise
                mkdirs(posixpath.dirname(directory))
                sftp.mkdir(directory)
                progress(f"Created {directory}")
            directories.add(directory)
        for local, remote in pending:
            check()
            stage = f"Upload {local.name} → {remote}"
            local_stat = local.stat()
            try:
                if sftp.stat(remote).st_mtime >= int(local_stat.st_mtime):
                    skipped += 1
                    progress(f"Skipped unchanged/newer: {remote}")
                    continue
            except OSError as error:
                if error.errno != errno.ENOENT:
                    raise
            mkdirs(posixpath.dirname(remote))
            temporary = remote + "." + uuid.uuid4().hex[:12] + ".upload"
            progress(stage)
            sftp.put(str(local), temporary, callback=lambda sent, total: check())
            check()
            if local.stat().st_mtime_ns != local_stat.st_mtime_ns or local.stat().st_size != local_stat.st_size:
                raise ValueError(f"Local file changed during upload: {local}")
            sftp.utime(temporary, (int(local_stat.st_atime), int(local_stat.st_mtime)))
            check()
            sftp.posix_rename(temporary, remote)
            copied += 1
        progress(f"Completed: {copied} uploaded, {skipped} unchanged/newer.")
    except Exception as error:
        if cancel.is_set():
            raise Cancelled("Upload cancelled. Local files are retained.") from error
        raise ValueError(f"{stage}: {error}\nLocal files are retained; retry using Upload.") from error
    finally:
        stop_watcher.set()
        client.close()
