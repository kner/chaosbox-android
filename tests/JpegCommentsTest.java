package local.sshcopy;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JpegCommentsTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static byte[] segment(String comment) throws Exception {
        java.lang.reflect.Method method = EditorActivity.ImageProcessor.class.getDeclaredMethod("exifSegment", String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, comment);
    }
    static String read(byte[] jpg) throws Exception {
        return EditorActivity.ImageProcessor.readUserComment(new ByteArrayInputStream(jpg));
    }
    static byte[] join(byte[]... chunks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
    static byte[] exif(ByteOrder order, boolean pointer, boolean direct) {
        int size = pointer ? 62 : 26;
        ByteBuffer b = ByteBuffer.allocate(size).order(order);
        b.put((byte)(order == ByteOrder.LITTLE_ENDIAN ? 'I' : 'M'));
        b.put(b.get(0)).putShort((short)42).putInt(8);
        b.putShort((short)(pointer || direct ? 2 : 1));
        b.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0);
        if (pointer) b.putShort((short)0x8769).putShort((short)4).putInt(1).putInt(38);
        if (direct) throw new IllegalArgumentException();
        b.putInt(0);
        if (pointer) {
            b.putShort((short)1).putShort((short)0x9000).putShort((short)7).putInt(4);
            b.put(new byte[]{'0','2','3','1'}).putInt(0);
        }
        ByteBuffer app = ByteBuffer.allocate(size+10).order(ByteOrder.BIG_ENDIAN);
        app.put((byte)255).put((byte)225).putShort((short)(size+8));
        app.put(new byte[]{'E','x','i','f',0,0}).put(b.array());
        return app.array();
    }
    public static void main(String[] args) throws Exception {
        byte[] soi = {(byte)255,(byte)216};
        byte[] app2 = {(byte)255,(byte)226,0,6,1,2,3,4};
        byte[] image = new byte[150000];
        new Random(42).nextBytes(image);
        image[0]=(byte)255; image[1]=(byte)218;
        image[image.length-2]=(byte)255; image[image.length-1]=(byte)217;
        String comment = "{\"comment\":\"Grüße – 日本語 😀\"}";
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            for (boolean pointer : new boolean[]{false,true}) {
                byte[] original = join(soi, app2, exif(order,pointer,false), image);
                byte[] result = JpegComments.update(original, comment, segment(comment));
                check(read(result).equals(comment), "UTF-8 roundtrip");
                check(Arrays.equals(Arrays.copyOfRange(result,result.length-image.length,result.length),image),
                        "Image bytes changed");
                check(Arrays.equals(Arrays.copyOfRange(result,2,10),app2),"APP2 changed");
                check(result.length > 102400, "Unexpected size limit");
                // Original TIFF orientation data remains at its original offset.
                check(Arrays.equals(Arrays.copyOfRange(original,20+10,20+22),
                        Arrays.copyOfRange(result,20+10,20+22)), "Orientation changed");
                int size=result.length;
                for(int i=0;i<20;i++) result=JpegComments.update(result,comment,segment(comment));
                check(result.length==size,"Repeated saves grew EXIF");
                String longer=comment+" längerer Kommentar".repeat(10);
                result=JpegComments.update(result,longer,segment(longer));
                check(read(result).equals(longer),"Longer comment failed");
                result=JpegComments.update(result,"kurz",segment("kurz"));
                check(read(result).equals("kurz"),"Shorter comment failed");
            }
        }
        byte[] resizedSource = join(soi, exif(ByteOrder.LITTLE_ENDIAN, false, false), image);
        byte[] retained = JpegComments.resizedExifSegment(resizedSource, comment, segment(comment));
        ByteBuffer tiff = ByteBuffer.wrap(retained, 10, retained.length - 10).slice()
                .order(ByteOrder.LITTLE_ENDIAN);
        int ifd = tiff.getInt(4);
        boolean upright = false;
        for (int i = 0, count = tiff.getShort(ifd) & 65535; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if ((tiff.getShort(entry) & 65535) == 0x0112)
                upright = tiff.getShort(entry + 8) == 1;
        }
        check(upright, "Resized JPEG retains EXIF with orientation 1");
        check(read(join(soi, retained, image)).equals(comment), "Resized JPEG comment missing");
        byte[] fresh=JpegComments.update(join(soi,app2,image),comment,segment(comment));
        check(read(fresh).equals(comment),"No EXIF insert failed");
        check(new String(segment(comment),StandardCharsets.ISO_8859_1)
                .contains(new String(comment.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1)),
                "Payload is not UTF-8");
        byte[] legacyText = "Grüße früher".getBytes(StandardCharsets.UTF_16LE);
        byte[] legacy = segment(" ".repeat(legacyText.length));
        System.arraycopy("UNICODE\0".getBytes(StandardCharsets.US_ASCII),0,legacy,54,8);
        System.arraycopy(legacyText,0,legacy,62,legacyText.length);
        check(read(join(soi,legacy,image)).equals("Grüße früher"),"Legacy Unicode unreadable");
        byte[] ascii = segment("old ASCII");
        System.arraycopy(new byte[]{'A','S','C','I','I',0,0,0},0,ascii,54,8);
        check(read(join(soi,ascii,image)).equals("old ASCII"),"Legacy ASCII unreadable");
        byte[] corrupt = join(soi,exif(ByteOrder.LITTLE_ENDIAN,false,false),image);
        corrupt[14]=(byte)255; corrupt[15]=(byte)255; corrupt[16]=(byte)255; corrupt[17]=(byte)127;
        try { JpegComments.update(corrupt,comment,segment(comment)); throw new AssertionError("Corrupt TIFF accepted"); }
        catch(IOException expected) { }
        try { JpegComments.update(new byte[]{1,2,3,4},"",segment("")); throw new AssertionError("Invalid JPEG accepted"); }
        catch(IOException expected) { }
        System.out.println("PASS: UTF-8, both TIFF byte orders, existing/new EXIF, preserved image and orientation, >LIMIT, repeated saves, legacy ASCII/Unicode, corrupt EXIF, invalid JPEG");
    }
}
