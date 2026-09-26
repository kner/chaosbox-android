#!/usr/bin/env python3
"""MP4 interoperability checks. Requires a JDK, FFmpeg/ffprobe, and ExifTool."""
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(args, text=True).strip()


with tempfile.TemporaryDirectory(prefix="chaosbox-mp4-") as temporary:
    folder = Path(temporary)
    classes = folder / "classes"
    classes.mkdir()
    run("javac", "-d", str(classes),
        str(ROOT / "app/src/main/java/local/sshcopy/Mp4Comments.java"),
        str(ROOT / "tests/Mp4CommentsTest.java"))

    def java(*args):
        return run("java", "-cp", str(classes), "local.sshcopy.Mp4CommentsTest", *map(str, args))

    print(java())
    metadata = json.dumps({"box": "A11", "anzahl": 3, "device": "Camera",
                           "alias": "Test", "category": "Video", "package": "P1",
                           "comment": "Grüße 🎬\n東京", "created": "2026-09-26",
                           "modified": "2026-09-27"}, ensure_ascii=False)

    def probe(file):
        return json.loads(run("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(file)))

    def hashes(file):
        return run("ffmpeg", "-v", "error", "-i", str(file), "-map", "0:v", "-map", "0:a",
                   "-c", "copy", "-f", "streamhash", "-")

    def quicktime_meta(data):
        """Convert only the meta header in a tail-moov fixture; media offsets stay put."""
        result = bytearray()
        offset = 0
        while offset < len(data):
            size, kind = struct.unpack_from(">I4s", data, offset)
            assert size >= 8 and offset + size <= len(data)
            payload = data[offset + 8:offset + size]
            if kind == b"moov":
                payload = quicktime_meta(payload)
            elif kind == b"udta":
                # QuickTime puts headerless meta directly under moov, unlike ISO udta/meta.
                assert struct.unpack_from(">I4s", payload) == (len(payload), b"meta")
                result.extend(quicktime_meta(payload))
                offset += size
                continue
            elif kind == b"meta":
                assert payload[:4] == bytes(4) and payload[8:12] == b"hdlr"
                payload = payload[4:]
            result.extend(struct.pack(">I4s", len(payload) + 8, kind) + payload)
            offset += size
        return result

    for layout in ("tail", "faststart", "mdta", "quicktime", "quicktime-mdta"):
        source = folder / (layout + "-source.mp4")
        flags = ["-movflags", "+faststart"] if layout == "faststart" else ["-movflags", "use_metadata_tags"] if layout in ("mdta", "quicktime-mdta") else []
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1",
            "-c:v", "mpeg4", "-c:a", "aac", "-metadata", "title=Preserve this title",
            "-metadata", "comment=Original comment", *flags, str(source))
        if layout.startswith("quicktime"):
            source.write_bytes(quicktime_meta(source.read_bytes()))
        output = folder / (layout + ".mp4")
        shutil.copyfile(source, output)
        assert java("read", source) == "Original comment"
        before = hashes(source)
        java("write", output, metadata)
        assert java("read", output) == metadata
        assert run("exiftool", "-s3", "-ItemList:Comment", str(output)) == metadata
        assert hashes(output) == before, "Encoded audio/video changed"
        assert probe(output)["format"]["tags"]["comment"] == metadata, "FFprobe sees a stale Comment"
        assert run("exiftool", "-s3", "-Title", str(output)) == "Preserve this title"
        assert [s["codec_name"] for s in probe(output)["streams"]] == [s["codec_name"] for s in probe(source)["streams"]]
        run("ffmpeg", "-v", "error", "-i", str(output), "-f", "null", "-")
        size = output.stat().st_size
        java("write", output, metadata)
        assert output.stat().st_size == size, "Repeated write increased file size"
        assert hashes(output) == before
        # An independent writer can replace our Comment, and our reader sees it.
        run("exiftool", "-overwrite_original", "-ItemList:Comment=External Grüße", str(output))
        assert java("read", output) == "External Grüße"
        print(f"PASS: {layout}: ExifTool round trip, title, streams, decoding, repeat saves")
