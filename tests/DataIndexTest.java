package local.sshcopy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import org.json.*;

public final class DataIndexTest {
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("data-index-test-");
        try {
            Files.createDirectories(root.resolve("JPG/sub"));
            Files.createDirectories(root.resolve("boxes"));
            Files.write(root.resolve("JPG/sub/photo.JPG"), new byte[]{1});
            Files.createSymbolicLink(root.resolve("JPG/link.jpg"), root.resolve("JPG/sub/photo.JPG"));
            Files.write(root.resolve("boxes/Box7.json"), ("[{\"device\":\"Sensor\",\"anzahl\":2,\"comment\":\"Grüße\"},"
                    + "{\"device\":\"Other\",\"anzahl\":5}]").getBytes(StandardCharsets.UTF_8));
            DataIndex.ImageMetadata reader = file -> new JSONObject("{\"box\":\"Box7\",\"device\":\"Sensor\",\"path\":\"wrong\"}");
            List<DataIndex.Entry> entries = DataIndex.rebuild(root.toFile(), reader);
            check(entries.size() == 3, "Recursive sources, symlink exclusion or multi-record loading");
            JSONArray index = new JSONArray(LocalData.read(root.resolve("data/records.json").toFile()));
            check(index.length() == 3 && index.getJSONObject(0).getString("path").equals("photo.JPG"), "Filename path");
            check(index.getJSONObject(1).getString("path").equals("Box7.json"), "JSON source path");
            Map<String, String> query = new LinkedHashMap<>();
            query.put("box", "^box7$"); query.put("device", "sen.*"); query.put("anzahl", "^[12]$");
            List<DataIndex.Entry> matches = DataIndex.search(entries, DataIndex.compile(query));
            check(matches.size() == 1 && matches.get(0).data.getString("comment").equals("Grüße"), "Regex AND/case matching");
            check(DataIndex.imageFor(matches.get(0), entries).getName().equals("photo.JPG"), "Related photo");
            check(DataIndex.imageFor(entries.get(2), entries) == null, "Unrelated photo");
            query.clear(); query.put("comment", "GRÜ");
            check(DataIndex.search(entries, DataIndex.compile(query)).size() == 1, "Unicode partial matching");
            query.clear(); query.put("device", "");
            check(DataIndex.search(entries, DataIndex.compile(query)).size() == 3, "Empty query");
            try { DataIndex.compile(Collections.singletonMap("device", "[")); throw new AssertionError("Invalid regex"); }
            catch (java.util.regex.PatternSyntaxException expected) {}
            List<DataIndex.Entry> cross = Arrays.asList(
                    new DataIndex.Entry(new JSONObject("{\"device\":\"Sensor\",\"alias\":\"Fühler\",\"category\":\"Heizung\",\"comment\":\"Keller\"}"), new File("test.json"), false));
            for (String input : new String[]{"alias", "comment"}) {
                for (String value : new String[]{"^sensor$", "^fühler$", "^keller$"})
                    check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap(input, value))).size() == 1,
                            "Cross-field match: " + input + " / " + value);
                check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap(input, "Heizung"))).isEmpty(),
                        "Alias/comment must not search category");
            }
            for (String value : new String[]{"^heizung$", "^keller$"})
                check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("category", value))).size() == 1,
                        "Category searches category and comment");
            check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("category", "Sensor"))).isEmpty(),
                    "Category must not search device");
            check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("device", "^keller$"))).size() == 1,
                    "Device searches comment");
            check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("device", "^sensor$"))).size() == 1,
                    "Device still searches device");
            check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("device", "Fühler"))).isEmpty(),
                    "Device must not search alias");
            query.clear(); query.put("alias", "Sensor"); query.put("category", "Keller");
            check(DataIndex.search(cross, DataIndex.compile(query)).size() == 1, "Cross-field AND match");
            query.put("comment", "Absent");
            check(DataIndex.search(cross, DataIndex.compile(query)).isEmpty(), "Cross-field AND rejection");
            check(DataIndex.search(cross, DataIndex.compile(Collections.singletonMap("alias", "Sensor.*Fühler"))).isEmpty(),
                    "Regex must not bridge separate fields");
            String old = LocalData.read(root.resolve("data/records.json").toFile());
            Files.write(root.resolve("boxes/broken.json"), "[broken".getBytes(StandardCharsets.UTF_8));
            try { DataIndex.rebuild(root.toFile(), reader); throw new AssertionError("Broken source"); }
            catch (IOException expected) {}
            check(old.equals(LocalData.read(root.resolve("data/records.json").toFile())), "Previous index preservation");
            Files.delete(root.resolve("boxes/broken.json"));
            Files.write(root.resolve("boxes/Box7.json"), "[{\"device\":\"New\"}]".getBytes(StandardCharsets.UTF_8));
            check(DataIndex.rebuild(root.toFile(), reader).size() == 2, "Refresh replaces stale records");
            Files.delete(root.resolve("JPG/sub/photo.JPG"));
            Files.delete(root.resolve("boxes/Box7.json"));
            check(DataIndex.rebuild(root.toFile(), reader).isEmpty(), "Empty catalogue");
            System.out.println("PASS: catalogue, recursive scan, source paths, refresh, preservation, regex search and related image");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }
}
