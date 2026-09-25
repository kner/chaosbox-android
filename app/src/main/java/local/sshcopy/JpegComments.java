package local.sshcopy;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Updates only the EXIF UserComment, retaining all other JPEG bytes and TIFF offsets. */
final class JpegComments {
    static byte[] update(byte[] jpeg, String comment, byte[] newSegment) throws IOException {
        if (jpeg.length < 4 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216)
            throw new IOException("Invalid JPG file");
        int p = 2;
        while (p + 4 <= jpeg.length) {
            if ((jpeg[p] & 255) != 255) throw new IOException("Invalid JPG segment");
            int marker = jpeg[p + 1] & 255;
            if (marker == 218 || marker == 217) break;
            int length = ((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255);
            if (length < 2 || p + 2 + length > jpeg.length) throw new IOException("Invalid JPG length");
            if (marker == 225 && length >= 16 && jpeg[p+4]=='E' && jpeg[p+5]=='x'
                    && jpeg[p+6]=='i' && jpeg[p+7]=='f' && jpeg[p+8]==0 && jpeg[p+9]==0) {
                byte[] tiff = Arrays.copyOfRange(jpeg, p + 10, p + 2 + length);
                byte[] updated = updateTiff(tiff, comment);
                ByteArrayOutputStream segment = new ByteArrayOutputStream();
                segment.write(255); segment.write(225);
                int size = updated.length + 8;
                if (size > 65535) throw new IOException("Comment is too long");
                segment.write(size >> 8); segment.write(size & 255);
                segment.write(new byte[]{'E','x','i','f',0,0}); segment.write(updated);
                return splice(jpeg, p, p + 2 + length, segment.toByteArray());
            }
            p += 2 + length;
        }
        return splice(jpeg, 2, 2, newSegment);
    }

    /** Retain existing EXIF tags when pixels are resized; orientation is baked into the new pixels. */
    static byte[] resizedExifSegment(byte[] jpeg, String comment, byte[] fallback) throws IOException {
        int p = 2;
        while (p + 4 <= jpeg.length) {
            if ((jpeg[p] & 255) != 255) throw new IOException("Invalid JPG segment");
            int marker = jpeg[p + 1] & 255;
            if (marker == 218 || marker == 217) break;
            int length = ((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255);
            if (length < 2 || p + 2 + length > jpeg.length) throw new IOException("Invalid JPG length");
            if (marker == 225 && length >= 16 && jpeg[p+4]=='E' && jpeg[p+5]=='x'
                    && jpeg[p+6]=='i' && jpeg[p+7]=='f' && jpeg[p+8]==0 && jpeg[p+9]==0) {
                byte[] tiff = Arrays.copyOfRange(jpeg, p + 10, p + 2 + length);
                byte[] updated = updateTiff(tiff, comment);
                ByteBuffer tags = ByteBuffer.wrap(updated).order(updated[0] == 'I'
                        ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                int orientation = entry(tags, tags.getInt(4), 0x0112);
                if (orientation >= 0 && (tags.getShort(orientation + 2) & 65535) == 3
                        && tags.getInt(orientation + 4) == 1)
                    tags.putShort(orientation + 8, (short) 1);
                ByteArrayOutputStream segment = new ByteArrayOutputStream();
                segment.write(255); segment.write(225);
                int size = updated.length + 8;
                if (size > 65535) throw new IOException("Comment is too long");
                segment.write(size >> 8); segment.write(size & 255);
                segment.write(new byte[]{'E','x','i','f',0,0}); segment.write(updated);
                return segment.toByteArray();
            }
            p += 2 + length;
        }
        return fallback;
    }

    private static byte[] splice(byte[] bytes, int start, int end, byte[] replacement) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes, 0, start); out.write(replacement);
        out.write(bytes, end, bytes.length - end);
        return out.toByteArray();
    }

    private static int entry(ByteBuffer b, int offset, int tag) throws IOException {
        if (offset < 8 || offset > b.limit() - 6) throw new IOException("Invalid EXIF directory");
        int count = b.getShort(offset) & 65535;
        if ((long)offset + 6 + count * 12L > b.limit()) throw new IOException("Invalid EXIF entries");
        for (int i = 0; i < count; i++) {
            int at = offset + 2 + 12 * i;
            if ((b.getShort(at) & 65535) == tag) return at;
        }
        return -1;
    }

    private static byte[] updateTiff(byte[] original, String comment) throws IOException {
        try {
            if (original.length < 8 || !((original[0]=='I' && original[1]=='I')
                    || (original[0]=='M' && original[1]=='M'))) throw new IOException("Invalid TIFF");
            ByteOrder order = original[0]=='I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer old = ByteBuffer.wrap(original).order(order);
            if (old.getShort(2) != 42) throw new IOException("Invalid TIFF");
            byte[] text = comment.getBytes(StandardCharsets.UTF_8);
            byte[] user = new byte[text.length + 8]; // Undefined encoding identifier; payload is UTF-8.
            System.arraycopy(text, 0, user, 8, text.length);
            int ifd0 = old.getInt(4);
            int direct = entry(old, ifd0, 0x9286);
            int pointer = entry(old, ifd0, 0x8769);
            int exif = pointer < 0 ? -1 : old.getInt(pointer + 8);
            int tag = direct >= 0 ? direct : (exif < 0 ? -1 : entry(old, exif, 0x9286));
            ByteBuffer b = ByteBuffer.allocate(original.length + user.length + 65536).order(order);
            b.put(original);
            if (tag < 0) {
                // Append a replacement directory; existing data and pointers stay valid.
                int directory = exif < 0 ? -1 : exif;
                int count = directory < 0 ? 0 : old.getShort(directory) & 65535;
                int newIfd = b.position();
                b.putShort((short)(count + 1));
                int insert = 0;
                while (insert < count && (old.getShort(directory + 2 + insert*12) & 65535) < 0x9286) insert++;
                if (insert > 0) b.put(original, directory + 2, insert*12);
                tag = b.position();
                b.putShort((short)0x9286).putShort((short)7).putInt(0).putInt(0);
                if (insert < count) b.put(original, directory + 2 + insert*12, (count-insert)*12);
                b.putInt(directory < 0 ? 0 : old.getInt(directory + 2 + count*12));
                if (pointer >= 0) b.putInt(pointer + 8, newIfd);
                else {
                    int n = old.getShort(ifd0) & 65535;
                    int replacement = b.position();
                    b.putShort((short)(n+1));
                    int i = 0;
                    while (i < n && (old.getShort(ifd0+2+i*12)&65535) < 0x8769) i++;
                    b.put(original, ifd0+2, i*12);
                    b.putShort((short)0x8769).putShort((short)4).putInt(1).putInt(newIfd);
                    b.put(original, ifd0+2+i*12, (n-i)*12);
                    b.putInt(old.getInt(ifd0+2+n*12));
                    b.putInt(4, replacement);
                }
            }
            int start = b.position();
            if (tag < original.length) {
                int oldLength = old.getInt(tag + 4), oldStart = old.getInt(tag + 8);
                if (oldLength > 4 && oldStart >= 8 && (long)oldStart + oldLength <= original.length) {
                    if (user.length <= oldLength || oldStart + oldLength == original.length) start = oldStart;
                }
            }
            int end = Math.max(b.position(), start + user.length);
            b.position(start); b.put(user);
            b.putShort(tag+2, (short)7).putInt(tag+4, user.length).putInt(tag+8, start);
            if (end + 8 > 65535) throw new IOException("Comment is too long");
            return Arrays.copyOf(b.array(), end);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("Corrupted EXIF data", e);
        }
    }
}
