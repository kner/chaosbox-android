package local.sshcopy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

public final class Mp4CommentsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
    private static byte[] box(String type, byte[] payload) throws IOException {
        return join(ByteBuffer.allocate(4).putInt(payload.length + 8).array(),
                type.getBytes(StandardCharsets.ISO_8859_1), payload);
    }
    private static String read(Path file) throws IOException {
        return Mp4Comments.read(Files.newInputStream(file));
    }
    private static void rejected(Path file, byte[] content) throws IOException {
        Files.write(file, content);
        try {
            Mp4Comments.write(file.toFile(), "test");
            throw new AssertionError("Unsafe MP4 accepted");
        } catch (IOException expected) {
            check(Arrays.equals(content, Files.readAllBytes(file)), "Rejected input remains unchanged");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Path file = Path.of(args[1]);
            if (args[0].equals("write")) Mp4Comments.write(file.toFile(), args[2]);
            else System.out.print(read(file));
            return;
        }
        Path file = Files.createTempFile("mp4-comments-", ".mp4");
        try {
            byte[] ftyp = box("ftyp", "isom\0\0\0\0isommp42".getBytes(StandardCharsets.ISO_8859_1));
            byte[] track = box("trak", new byte[]{1, 2, 3, 4, 5});
            byte[] unknown = box("uuid", new byte[24]);
            byte[] movie = box("moov", join(track, unknown));
            byte[] media = box("mdat", new byte[]{10, 20, 30, 40, 50, 60});
            String comment = "{\"box\":\"A11\",\"comment\":\"Grüße 🎬\\n東京\"}";
            for (boolean fastStart : new boolean[]{true, false}) {
                byte[] original = fastStart ? join(ftyp, movie, media) : join(ftyp, media, movie);
                Files.write(file, original);
                check(read(file).isEmpty(), "Missing Comment is empty");
                int mediaOffset = fastStart ? ftyp.length + movie.length : ftyp.length;
                Mp4Comments.write(file.toFile(), comment);
                check(read(file).equals(comment), "Unicode JSON round trip");
                byte[] saved = Files.readAllBytes(file);
                check(Arrays.equals(media, Arrays.copyOfRange(saved, mediaOffset, mediaOffset + media.length)),
                        "Media box remains at the exact original offset");
                check(contains(saved, track) && contains(saved, unknown), "Tracks and unknown metadata preserved");
                long size = Files.size(file);
                Mp4Comments.write(file.toFile(), comment);
                check(Files.size(file) == size, "Repeated saves do not grow the file");
                Mp4Comments.write(file.toFile(), "short");
                check(read(file).equals("short"), "Replace an existing Comment");
                Mp4Comments.write(file.toFile(), "");
                check(read(file).isEmpty(), "Clear Comment");
            }
            // QuickTime meta atoms omit the four version/flags bytes of ISO MP4 meta boxes.
            byte[] handler = box("hdlr", join(new byte[8], "mdir".getBytes(StandardCharsets.US_ASCII), new byte[13]));
            byte[] oldValue = box("\u00a9cmt", box("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0},
                    "Original".getBytes(StandardCharsets.UTF_8))));
            for (boolean nested : new boolean[]{false, true}) {
                byte[] meta = box("meta", join(handler, box("ilst", oldValue)));
                byte[] metadata = nested ? box("udta", meta) : meta;
                Files.write(file, join(ftyp, media, box("moov", join(track, metadata))));
                check(read(file).equals("Original"), "Read QuickTime meta without version/flags");
                Mp4Comments.write(file.toFile(), comment);
                check(read(file).equals(comment), "Save QuickTime meta without version/flags");
                check(contains(Files.readAllBytes(file), box("meta", join(handler,
                        box("ilst", box("\u00a9cmt", box("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0},
                                comment.getBytes(StandardCharsets.UTF_8)))))))), "Preserve QuickTime meta layout");
            }
            byte[] openMedia = media.clone(); Arrays.fill(openMedia, 0, 4, (byte) 0);
            Files.write(file, join(ftyp, movie, openMedia));
            Mp4Comments.write(file.toFile(), comment);
            check(read(file).equals(comment), "Open-ended mdat becomes bounded before appending metadata");
            check(Arrays.equals(media, Arrays.copyOfRange(Files.readAllBytes(file), ftyp.length + movie.length,
                    ftyp.length + movie.length + media.length)), "Open-ended media payload unchanged");
            byte[] extendedMovie = join(ByteBuffer.allocate(4).putInt(1).array(),
                    "moov".getBytes(StandardCharsets.US_ASCII), ByteBuffer.allocate(8).putLong(16 + track.length).array(), track);
            Files.write(file, join(ftyp, extendedMovie, media));
            Mp4Comments.write(file.toFile(), comment);
            check(read(file).equals(comment), "Extended-size movie box");
            byte[] openTrack = track.clone(); Arrays.fill(openTrack, 0, 4, (byte) 0);
            Files.write(file, join(ftyp, box("moov", openTrack), media));
            Mp4Comments.write(file.toFile(), comment);
            check(read(file).equals(comment), "Open-ended metadata child does not swallow the new Comment");
            // Sparse media checks 64-bit box sizes without allocating or reading a multi-GB payload.
            long largeMediaSize = 0x100000020L;
            try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
                sparse.setLength(0); sparse.write(ftyp);
                sparse.writeInt(1); sparse.writeBytes("mdat"); sparse.writeLong(largeMediaSize);
                sparse.seek(ftyp.length + largeMediaSize); sparse.write(movie);
            }
            Mp4Comments.write(file.toFile(), comment);
            check(read(file).equals(comment), "MP4 media larger than 4 GB uses bounded memory and 64-bit offsets");
            rejected(file, join(ftyp, box("moov", box("mvex", new byte[0])), media));
            rejected(file, join(ftyp, movie, box("moof", new byte[0]), media));
            rejected(file, join(ftyp, movie, movie, media));
            rejected(file, join(ftyp, movie, new byte[]{1, 2}));
            rejected(file, join(ftyp, box("moov", new byte[]{1, 2, 3}), media));
            rejected(file, join(ftyp, media, box("moov", box("meta", new byte[]{0, 0, 0}))));
            byte[] invalidHandler = handler.clone(); ByteBuffer.wrap(invalidHandler).putInt(handler.length + 100);
            rejected(file, join(ftyp, media, box("moov", box("meta", join(new byte[4], invalidHandler)))));
            rejected(file, join(ftyp, media, box("moov", box("meta", invalidHandler))));
            rejected(file, media);
            check(Mp4Comments.isVideo("CLIP.MP4") && !Mp4Comments.isVideo("clip.mp4.jpg"), "File type detection");
            System.out.println("PASS: MP4 comments, Unicode, payload offsets, unknown boxes, repeated saves, invalid/fragmented files");
        } finally { Files.deleteIfExists(file); }
    }
    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
