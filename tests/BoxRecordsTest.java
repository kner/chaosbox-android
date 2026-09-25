package local.sshcopy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.File;
import org.json.JSONArray;
public final class BoxRecordsTest {
    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Paths.get(args[0]), "box-update-test-");
        try {
            File dir = root.resolve("boxes").toFile();
            File first = BoxRecords.save(dir, "Box 7", "{\"anzahl\":0,\"comment\":\"Grüße\"}");
            File second = BoxRecords.save(dir, "Box 7", "{\"anzahl\":3}");
            JSONArray records = new JSONArray(read(first));
            if (!first.equals(second) || !first.getName().equals("box 7.json") || records.length()!=1
                    || !records.getJSONObject(0).getString("comment").equals("Grüße")
                    || records.getJSONObject(0).getInt("anzahl")!=3) throw new AssertionError("Update failed");
            File lowercase = BoxRecords.save(dir, "A11", "{\"device\":\"A\",\"anzahl\":1}");
            if (!lowercase.getName().equals("a11.json")) throw new AssertionError("Lowercase filename expected");
            BoxRecords.save(dir, "a11.JSON", "{\"device\":\"A\",\"anzahl\":2}");
            if (BoxRecords.load(dir, "A11.JSON").get(0).getInt("anzahl") != 2
                    || BoxRecords.load(dir, "a11").size() != 1
                    || new File(dir, "A11.json").exists())
                throw new AssertionError("Case-insensitive box update/load failed");
            java.util.Locale previousLocale = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
                if (!BoxRecords.filename(" I11.JSON ").equals("i11.json"))
                    throw new AssertionError("Filename depends on device locale");
            } finally { java.util.Locale.setDefault(previousLocale); }
            File legacy = new File(dir, "old.json");
            Files.write(legacy.toPath(), "{\"old\":true}".getBytes(StandardCharsets.UTF_8));
            BoxRecords.save(dir, "old", "{}");
            if (new JSONArray(read(legacy)).length()!=1) throw new AssertionError("Legacy object lost");
            File sensor = BoxRecords.save(dir, "sensors", "{\"device\":\"A\",\"count\":2,\"pack\":\"old\",\"created\":\"original\",\"custom\":{\"keep\":true}}");
            BoxRecords.save(dir, "sensors", "{\"device\":\"A\",\"anzahl\":5,\"package\":\"new\",\"comment\":\"Grüße 😀\",\"created\":\"replacement\",\"modified\":\"now\"}");
            JSONArray updated = new JSONArray(read(sensor));
            org.json.JSONObject item = updated.getJSONObject(0);
            if (updated.length()!=1 || item.getInt("count")!=5 || item.getInt("anzahl")!=5
                    || !item.getString("pack").equals("new") || !item.getString("package").equals("new")
                    || !item.getString("created").equals("original")
                    || !item.getJSONObject("custom").getBoolean("keep")
                    || !item.getString("comment").equals("Grüße 😀")) throw new AssertionError("Merged update failed");
            long previousTime = sensor.lastModified();
            BoxRecords.save(dir, "sensors", "{\"device\":\"B\",\"anzahl\":1}");
            if (new JSONArray(read(sensor)).length()!=2) throw new AssertionError("New device not appended");
            BoxRecords.save(dir, "sensors.json", "{\"device\":\"B\",\"anzahl\":4}");
            updated = new JSONArray(read(sensor));
            if (updated.length()!=2 || updated.getJSONObject(1).getInt("anzahl")!=4
                    || updated.getJSONObject(0).getInt("count")!=5
                    || sensor.lastModified()/1000 <= previousTime/1000
                    || new File(dir, "sensors.json.json").exists()) throw new AssertionError("Repeated save or filename failed");
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
            BoxRecords.save(dir, "devices", "{\"device\":\"Sensor\",\"anzahl\":9}", 1);
            loaded = BoxRecords.load(dir, "devices");
            if (loaded.size()!=2 || loaded.get(0).getInt("anzahl")!=2 || loaded.get(1).getInt("anzahl")!=9)
                throw new AssertionError("Wrong duplicate updated");
            for (int quantity : new int[]{0, 12, 3}) {
                BoxRecords.save(dir, "devices", "{\"device\":\"Sensor\",\"anzahl\":" + quantity + "}", 1);
                loaded = BoxRecords.load(dir, "devices");
                if (loaded.size()!=2 || loaded.get(0).getInt("anzahl")!=2
                        || loaded.get(1).getInt("anzahl")!=quantity)
                    throw new AssertionError("Quantity change appended or changed another record");
            }
            BoxRecords.save(dir, "devices", "{\"device\":\"Sensor korrigiert\",\"anzahl\":4}", 1);
            loaded = BoxRecords.load(dir, "devices");
            if (loaded.size()!=2 || !loaded.get(1).getString("device").equals("Sensor korrigiert")
                    || loaded.get(1).getInt("anzahl")!=4)
                throw new AssertionError("Selected record identity lost when label changed");
            String beforeInvalidSelection = read(new File(dir, "devices.json"));
            try {
                BoxRecords.save(dir, "devices", "{\"device\":\"Sensor\",\"anzahl\":99}", 2);
                throw new AssertionError("Stale selection accepted");
            } catch (java.io.IOException expected) { }
            if (!beforeInvalidSelection.equals(read(new File(dir, "devices.json"))))
                throw new AssertionError("Stale selection modified the file");
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
            System.out.println("PASS: named file, update/new device, aliases, retained metadata, selected duplicate, repeated saves, UTF-8, corrupt preservation");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Path[] all = paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : all) Files.delete(path);
            }
        }
    }
}
