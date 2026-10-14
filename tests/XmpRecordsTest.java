package local.sshcopy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class XmpRecordsTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            byte[] source = Files.readAllBytes(Path.of(args[1]));
            if (args[0].equals("write")) Files.write(Path.of(args[2]), XmpRecords.write(JpegComments.update(source, null, new byte[0]), Files.readString(Path.of(args[2]+".json"))));
            else Files.writeString(Path.of(args[2]), XmpRecords.read(source, ""));
            return;
        }
        byte[] jpeg = {(byte)255,(byte)216,(byte)255,(byte)217};
        JSONObject record = new JSONObject("{\"box\":\"A11\",\"anzahl\":3,\"comment\":\"Grüße 東京 & < >\\nZeile\",\"created\":\"2026-10-14\"}");
        byte[] written = XmpRecords.write(jpeg, record.toString());
        JSONObject actual = new JSONObject(XmpRecords.read(written, "old"));
        for (String key : record.keySet()) if (!record.get(key).toString().equals(actual.get(key).toString())) throw new AssertionError(key);
        byte[] resized = XmpRecords.write(jpeg, record.toString(), written);
        if (!new JSONObject(XmpRecords.read(resized, "")).getString("box").equals("A11")) throw new AssertionError("resized XMP");
        record.put("comment", "");
        byte[] cleared = XmpRecords.write(written, record.toString());
        if (!new JSONObject(XmpRecords.read(cleared, "old")).getString("comment").isEmpty()) throw new AssertionError("cleared comment");
        if (!XmpRecords.read(jpeg, "legacy").equals("legacy")) throw new AssertionError("legacy");
        if (XmpRecords.write(cleared, record.toString()).length != cleared.length) throw new AssertionError("duplicate packets");
        System.out.println("XMP round-trip, Unicode, clearing, legacy fallback and repeated saves passed");
    }
}
