package local.sshcopy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class XmpRecordsTest {
    private static void snapshotRemoval() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document document = factory.newDocumentBuilder().newDocument();
        org.w3c.dom.Element root = document.createElement("root");
        document.appendChild(root);
        root.setAttributeNS(XmpRecords.NS, "cb:box", "attribute");
        org.w3c.dom.Node[] matches = new org.w3c.dom.Node[2];
        for (int i = 0; i < matches.length; i++) {
            matches[i] = document.createElementNS(XmpRecords.NS, "cb:box");
            root.appendChild(matches[i]);
        }
        // Reproduce Android DOM queries whose results retain removed nodes.
        org.w3c.dom.NodeList snapshot = new org.w3c.dom.NodeList() {
            public int getLength() { return matches.length; }
            public org.w3c.dom.Node item(int index) { return matches[index]; }
        };
        org.w3c.dom.Document androidDocument = (org.w3c.dom.Document) java.lang.reflect.Proxy.newProxyInstance(
            XmpRecordsTest.class.getClassLoader(), new Class<?>[]{org.w3c.dom.Document.class},
            (proxy, method, arguments) -> method.getName().equals("getElementsByTagNameNS")
                ? snapshot : method.invoke(document, arguments));
        java.lang.reflect.Method remove = XmpRecords.class.getDeclaredMethod("remove", org.w3c.dom.Document.class, String.class, String.class);
        remove.setAccessible(true);
        remove.invoke(null, androidDocument, XmpRecords.NS, "box");
        remove.invoke(null, androidDocument, XmpRecords.NS, "box");
        if (root.hasChildNodes() || root.hasAttributeNS(XmpRecords.NS, "box"))
            throw new AssertionError("snapshot properties were not removed");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            byte[] source = Files.readAllBytes(Path.of(args[1]));
            if (args[0].equals("write")) Files.write(Path.of(args[2]), XmpRecords.write(JpegComments.update(source, null, new byte[0]), Files.readString(Path.of(args[2]+".json"))));
            else Files.writeString(Path.of(args[2]), XmpRecords.read(source, ""));
            return;
        }
        snapshotRemoval();
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
        record.put("comment", JSONObject.NULL);
        byte[] nullComment = XmpRecords.write(written, record.toString());
        if (!new JSONObject(XmpRecords.read(nullComment, "old")).getString("comment").isEmpty())
            throw new AssertionError("JSON null must not become literal null in XMP");
        record.put("comment", "null");
        if (!new JSONObject(XmpRecords.read(XmpRecords.write(written, record.toString()), "")).getString("comment").equals("null"))
            throw new AssertionError("literal comment text must be preserved");
        System.out.println("XMP round-trip, Unicode, clearing, legacy fallback and repeated saves passed");
    }
}
