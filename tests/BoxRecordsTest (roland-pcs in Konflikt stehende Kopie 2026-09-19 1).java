package local.sshcopy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.File;
import org.json.JSONArray;
public final class BoxRecordsTest {
    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Paths.get(args[0]), "box-append-test-");
        try {
            File dir = root.resolve("boxes").toFile();
            File first = BoxRecords.save(dir, "Box 7", "{\"anzahl\":0,\"comment\":\"Grüße\"}");
            File second = BoxRecords.save(dir, "Box 7", "{\"anzahl\":3}");
            JSONArray records = new JSONArray(read(first));
            if (!first.equals(second) || !first.getName().equals("Box 7.json") || records.length()!=2
                    || !records.getJSONObject(0).getString("comment").equals("Grüße")
                    || records.getJSONObject(1).getInt("anzahl")!=3) throw new AssertionError("Append failed");
            File legacy = new File(dir, "old.json");
            Files.write(legacy.toPath(), "{\"old\":true}".getBytes(StandardCharsets.UTF_8));
            BoxRecords.save(dir, "old", "{}");
            if (new JSONArray(read(legacy)).length()!=2) throw new AssertionError("Legacy object lost");
            File bad = new File(dir, "bad.json");
            Files.write(bad.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
            try { BoxRecords.save(dir,"bad","{}"); throw new AssertionError("Corrupt accepted"); }
            catch (java.io.IOException expected) { }
            if (!read(bad).equals("broken")) throw new AssertionError("Corrupt file overwritten");
            for (String name : new String[]{"", "  ", "../escape", "a/b", "a\\b"}) {
                try { BoxRecords.save(dir,name,"{}"); throw new AssertionError("Invalid name accepted"); }
                catch (java.io.IOException expected) { }
            }
            Files.write(new File(dir, "devices.json").toPath(),
                    "[{\"device\":\"Sensor\",\"anzahl\":2},{\"device\":\"Sensor\",\"anzahl\":7}]".getBytes(StandardCharsets.UTF_8));
            java.util.List<org.json.JSONObject> loaded = BoxRecords.load(dir, "devices");
            if (loaded.size() != 2 || loaded.get(1).getInt("anzahl") != 7)
                throw new AssertionError("Duplicate device records lost");
            if (BoxRecords.load(dir, "devices.json").size() != 2)
                throw new AssertionError("Extension handling failed");
            if (BoxRecords.parseRecords("\uFEFF{\"device\":\"Grüße\"}").size() != 1)
                throw new AssertionError("Single record or BOM failed");
            String grouped = "{\"one\":{\"device\":\"A\"},\"two\":{\"device\":\"B\"}}";
            Files.write(new File(dir, "grouped.json").toPath(), grouped.getBytes(StandardCharsets.UTF_8));
            if (BoxRecords.load(dir, "grouped").size() != 2)
                throw new AssertionError("Keyed records failed");
            BoxRecords.save(dir, "grouped", "{\"device\":\"C\"}");
            if (BoxRecords.load(dir, "grouped").size() != 3)
                throw new AssertionError("Saving keyed records lost records");
            for (String invalid : new String[]{"[]", "{}", "[1]", "broken", "{} {}", "[{}] trailing"}) {
                try { BoxRecords.parseRecords(invalid); throw new AssertionError("Invalid JSON accepted: " + invalid); }
                catch (java.io.IOException expected) { }
            }
            System.out.println("PASS: JSON loading, device duplicates, keyed records, BOM, extensions, malformed input");
            System.out.println("PASS: named file, append, UTF-8, legacy object, corrupt preservation, invalid names");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Path[] all = paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : all) Files.delete(path);
            }
        }
    }
}
