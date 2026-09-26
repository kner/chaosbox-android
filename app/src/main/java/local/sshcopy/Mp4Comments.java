package local.sshcopy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MP4 ItemList Comment (©cmt). Media payloads and absolute chunk offsets stay unchanged. */
final class Mp4Comments {
    private static final int MAX_METADATA = 32 * 1024 * 1024;
    private static final String COMMENT = "\u00a9cmt";

    static boolean isVideo(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4");
    }

    static String read(InputStream source) throws IOException {
        if (source == null) throw new IOException("MP4 source is unavailable.");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(source))) {
            while (true) {
                int first = in.read();
                if (first < 0) throw new IOException("MP4 movie metadata is missing.");
                byte[] header = new byte[8];
                header[0] = (byte) first;
                in.readFully(header, 1, 7);
                long size = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
                String type = type(header, 4);
                int headerSize = 8;
                if (size == 1) { size = in.readLong(); headerSize = 16; }
                if (size == 0) {
                    if (!type.equals("moov")) throw new IOException("MP4 movie metadata is missing.");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        if (out.size() > MAX_METADATA - n) throw new IOException("MP4 metadata is too large.");
                        out.write(buffer, 0, n);
                    }
                    return readMovie(out.toByteArray());
                }
                if (size < headerSize) throw new IOException("Invalid MP4 box size.");
                long payload = size - headerSize;
                if (type.equals("moov")) {
                    if (payload > MAX_METADATA) throw new IOException("MP4 metadata is too large.");
                    byte[] data = new byte[(int) payload];
                    in.readFully(data);
                    return readMovie(data);
                }
                while (payload > 0) {
                    long skipped = in.skip(payload);
                    if (skipped == 0) {
                        if (in.read() < 0) throw new EOFException("Truncated MP4 box.");
                        skipped = 1;
                    }
                    payload -= skipped;
                }
            }
        }
    }

    private static String readMovie(byte[] movie) throws IOException {
        for (Box child : boxes(movie, 0)) {
            if (child.type.equals("udta")) {
                String found = readUserData(child.payload());
                if (found != null) return found;
            } else if (child.type.equals("meta")) {
                String found = readMeta(child.payload());
                if (found != null) return found;
            }
        }
        return "";
    }

    private static String readUserData(byte[] data) throws IOException {
        for (Box child : boxes(data, 0)) if (child.type.equals("meta")) {
            String found = readMeta(child.payload());
            if (found != null) return found;
        }
        return null;
    }

    private static String readMeta(byte[] data) throws IOException {
        Set<String> keys = commentKeys(data);
        // Prefer the interoperable ©cmt field over a legacy Keys comment.
        for (boolean keyed : new boolean[]{false, true}) {
            for (Box child : boxes(data, metaOffset(data))) if (child.type.equals("ilst")) {
                for (Box item : boxes(child.payload(), 0)) {
                    if (keyed ? !keys.contains(item.type) : !item.type.equals(COMMENT)) continue;
                    for (Box value : boxes(item.payload(), 0)) if (value.type.equals("data")) {
                        byte[] payload = value.payload();
                        if (payload.length < 8) throw new IOException("Invalid MP4 Comment data.");
                        int encoding = ByteBuffer.wrap(payload).getInt();
                        if (encoding == 1) return new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
                        if (encoding == 2) return new String(payload, 8, payload.length - 8, StandardCharsets.UTF_16BE);
                        throw new IOException("Unsupported MP4 Comment encoding: " + encoding);
                    }
                }
            }
        }
        return null;
    }

    private static Set<String> commentKeys(byte[] data) throws IOException {
        Set<String> result = new HashSet<>();
        for (Box child : boxes(data, metaOffset(data))) if (child.type.equals("keys")) {
            byte[] payload = child.payload();
            if (payload.length < 8) throw new IOException("Invalid MP4 metadata keys.");
            List<Box> keys = boxes(payload, 8);
            if (ByteBuffer.wrap(payload, 4, 4).getInt() != keys.size())
                throw new IOException("Invalid MP4 metadata key count.");
            for (int i = 0; i < keys.size(); i++) {
                String name = new String(keys.get(i).payload(), StandardCharsets.UTF_8);
                if (keys.get(i).type.equals("mdta")
                        && (name.equals("comment") || name.equals("com.apple.quicktime.comment")))
                    result.add(type(ByteBuffer.allocate(4).putInt(i + 1).array(), 0));
            }
        }
        return result;
    }

    /** Modify a disposable copy, never the user's source. Caller publishes it atomically. */
    static void write(File temporary, String comment) throws IOException {
        byte[] text = comment.getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_METADATA / 2) throw new IOException("MP4 Comment is too large.");
        byte[] value = box(COMMENT, box("data", join(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, text)));
        try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
            long length = file.length(), movieStart = -1, movieSize = 0;
            byte[] movie = null;
            List<long[]> openEnded = new ArrayList<>();
            for (long position = 0; position < length;) {
                if (length - position < 8) throw new IOException("Truncated MP4 box header.");
                file.seek(position);
                long size = Integer.toUnsignedLong(file.readInt());
                byte[] name = new byte[4]; file.readFully(name);
                String type = type(name, 0);
                int header = 8;
                if (size == 1) { size = file.readLong(); header = 16; }
                else if (size == 0) {
                    size = length - position;
                    if (!type.equals("moov")) openEnded.add(new long[]{position, size});
                }
                if (size < header || size > length - position) throw new IOException("Invalid MP4 box size.");
                if (type.equals("moof") || type.equals("mfra") || type.equals("sidx"))
                    throw new IOException("Fragmented MP4 files are not supported for editing.");
                if (type.equals("moov")) {
                    if (movie != null) throw new IOException("Multiple MP4 movie boxes are not supported.");
                    if (size - header > MAX_METADATA) throw new IOException("MP4 metadata is too large.");
                    movieStart = position; movieSize = size;
                    movie = new byte[(int) (size - header)]; file.readFully(movie);
                }
                position += size;
            }
            if (movie == null) throw new IOException("MP4 movie metadata is missing.");
            for (Box child : boxes(movie, 0)) if (child.type.equals("mvex"))
                throw new IOException("Fragmented MP4 files are not supported for editing.");
            byte[] updated = box("moov", updateMovie(movie, value));
            if (updated.length > MAX_METADATA) throw new IOException("MP4 metadata is too large.");
            for (long[] entry : openEnded) {
                if (entry[1] > 0xffffffffL) throw new IOException("Open-ended MP4 boxes over 4 GB are not supported.");
                file.seek(entry[0]); file.writeInt((int) entry[1]);
            }
            if (movieStart + movieSize == length) {
                // Repeated saves replace the trailing movie box without accumulating old metadata.
                file.seek(movieStart); file.write(updated); file.setLength(movieStart + updated.length);
            } else {
                // Keep every media byte at its original offset; stco/co64 need no rewriting.
                file.seek(movieStart); file.writeInt((int) movieSize); file.writeBytes("free");
                file.seek(length); file.write(updated);
            }
            file.getFD().sync();
        }
    }

    private static byte[] updateMovie(byte[] movie, byte[] comment) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean added = false;
        for (Box child : boxes(movie, 0)) {
            if (child.type.equals("udta")) {
                out.write(box("udta", updateUserData(child.payload(), added ? null : comment)));
                added = true;
            } else if (child.type.equals("meta") && itemListMeta(child.payload())) {
                out.write(box("meta", updateMeta(child.payload(), added ? null : comment)));
                added = true;
            } else out.write(child.raw());
        }
        if (!added) out.write(box("udta", updateUserData(new byte[0], comment)));
        return out.toByteArray();
    }

    private static byte[] updateUserData(byte[] data, byte[] comment) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean added = false;
        for (Box child : boxes(data, 0)) {
            if (child.type.equals("meta") && itemListMeta(child.payload())) {
                out.write(box("meta", updateMeta(child.payload(), added ? null : comment)));
                added = true;
            } else out.write(child.raw());
        }
        if (!added && comment != null) {
            byte[] handler = box("hdlr", join(new byte[8], join("mdir".getBytes(StandardCharsets.ISO_8859_1), new byte[13])));
            out.write(box("meta", join(new byte[4], join(handler, box("ilst", comment)))));
        }
        return out.toByteArray();
    }

    private static int metaOffset(byte[] data) throws IOException {
        // ISO meta is a FullBox (version/flags); QuickTime meta starts with hdlr.
        // Recognize the legacy layout explicitly instead of retrying malformed ISO boxes.
        if (data.length >= 8 && type(data, 4).equals("hdlr")) return 0;
        if (data.length < 4) throw new IOException("Truncated MP4 meta version/flags.");
        return 4;
    }

    private static boolean itemListMeta(byte[] data) throws IOException {
        for (Box child : boxes(data, metaOffset(data))) if (child.type.equals("hdlr")) {
            byte[] handler = child.payload();
            return handler.length >= 12 && (type(handler, 8).equals("mdir") || type(handler, 8).equals("mdta"));
        }
        // Existing iTunes metadata sometimes omits its handler.
        return true;
    }

    private static byte[] updateMeta(byte[] data, byte[] comment) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(data, 0, metaOffset(data));
        Set<String> keys = commentKeys(data);
        boolean added = false;
        for (Box child : boxes(data, metaOffset(data))) {
            if (child.type.equals("ilst")) {
                ByteArrayOutputStream items = new ByteArrayOutputStream();
                for (Box item : boxes(child.payload(), 0)) {
                    if (keys.contains(item.type)) {
                        // Keep existing Keys comments consistent for players that prefer that location.
                        if (comment != null) items.write(box(item.type, Arrays.copyOfRange(comment, 8, comment.length)));
                    } else if (!item.type.equals(COMMENT)) items.write(item.raw());
                }
                if (!added && comment != null) items.write(comment);
                out.write(box("ilst", items.toByteArray()));
                added = true;
            } else out.write(child.raw());
        }
        if (!added && comment != null) out.write(box("ilst", comment));
        return out.toByteArray();
    }

    private static final class Box {
        final byte[] data;
        final int start, end, header;
        final String type;
        Box(byte[] data, int start, int end, int header) {
            this.data = data; this.start = start; this.end = end; this.header = header;
            type = type(data, start + 4);
        }
        byte[] raw() {
            byte[] raw = Arrays.copyOfRange(data, start, end);
            // A size-zero child must not swallow newly appended siblings.
            if (ByteBuffer.wrap(raw).getInt() == 0) ByteBuffer.wrap(raw).putInt(raw.length);
            return raw;
        }
        byte[] payload() { return Arrays.copyOfRange(data, start + header, end); }
    }

    private static List<Box> boxes(byte[] data, int offset) throws IOException {
        if (offset > data.length) throw new IOException("Truncated MP4 metadata.");
        List<Box> result = new ArrayList<>();
        while (offset < data.length) {
            if (data.length - offset < 8) throw new IOException("Truncated MP4 metadata box.");
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(data, offset, 4).getInt());
            int header = 8;
            if (size == 1) {
                if (data.length - offset < 16) throw new IOException("Truncated MP4 extended box.");
                size = ByteBuffer.wrap(data, offset + 8, 8).getLong(); header = 16;
            } else if (size == 0) size = data.length - offset;
            if (size < header || size > data.length - offset) throw new IOException("Invalid MP4 metadata box size at offset " + offset
                    + " (type " + type(data, offset + 4).replaceAll("[^ -~]", "?")
                    + ", size " + size + ", remaining " + (data.length - offset) + ").");
            int end = offset + (int) size;
            result.add(new Box(data, offset, end, header)); offset = end;
        }
        return result;
    }

    private static String type(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
    }
    private static byte[] join(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(a); out.write(b); return out.toByteArray();
    }
    private static byte[] box(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length + 8); data.write(type.getBytes(StandardCharsets.ISO_8859_1)); data.write(payload);
        return out.toByteArray();
    }
    private Mp4Comments() { }
}
