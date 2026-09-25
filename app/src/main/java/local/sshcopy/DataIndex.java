package local.sshcopy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Rebuildable local catalogue; source files remain authoritative. */
final class DataIndex {
    interface ImageMetadata { JSONObject read(File image) throws Exception; }
    static final class Entry {
        final JSONObject data;
        final File source;
        final boolean image;
        Entry(JSONObject data, File source, boolean image) throws JSONException {
            this.data = new JSONObject(data.toString());
            if (!data.isNull("count")) this.data.put("anzahl", data.optInt("count", data.optInt("anzahl", 0)));
            if (!data.isNull("pack")) this.data.put("package", data.optString("pack", ""));
            this.data.put("path", source.getName());
            this.source = source;
            this.image = image;
        }
    }

    static List<Entry> rebuild(File root, ImageMetadata reader) throws IOException {
        List<Entry> entries = new ArrayList<>();
        collect(new File(root, "JPG"), true, reader, entries);
        collect(new File(root, "boxes"), false, reader, entries);
        return writeIndex(new File(root, "data"), entries);
    }

    static List<Entry> rebuild(StoragePaths paths, ImageMetadata reader) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (File file : paths.imageFiles()) collectFile(file.toPath(), true, reader, entries);
        for (File file : paths.jsonFiles()) collectFile(file.toPath(), false, reader, entries);
        return writeIndex(paths.index, entries);
    }

    private static List<Entry> writeIndex(File folder, List<Entry> entries) throws IOException {
        Path directory = folder.toPath();
        Files.createDirectories(directory);
        Path target = directory.resolve("records.json");
        if (Files.isSymbolicLink(target)) throw new IOException("Datendatei darf kein symbolischer Link sein.");
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.data);
        Path temp = Files.createTempFile(directory, ".records-", ".tmp");
        try {
            Files.write(temp, array.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (JSONException e) {
            throw new IOException("Datendatei konnte nicht erstellt werden", e);
        } finally {
            Files.deleteIfExists(temp);
        }
        return entries;
    }

    private static void collect(File folder, boolean images, ImageMetadata reader, List<Entry> entries)
            throws IOException {
        if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Kein Datenordner: " + folder);
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            Path[] files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted().toArray(Path[]::new);
            for (Path path : files) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (images ? !(name.endsWith(".jpg") || name.endsWith(".jpeg")) : !name.endsWith(".json")) continue;
                collectFile(path, images, reader, entries);
            }
        }
    }

    private static void collectFile(Path path, boolean images, ImageMetadata reader, List<Entry> entries)
            throws IOException {
        try {
            if (images) {
                entries.add(new Entry(reader.read(path.toFile()), path.toFile(), true));
            } else {
                String raw = LocalData.read(path.toFile());
                if (raw.trim().equals("[]")) return;
                for (JSONObject record : BoxRecords.parseRecords(raw)) {
                    if (record.optString("box", "").isEmpty()) {
                        record.put("box", path.getFileName().toString().replaceFirst("(?i)\\.json$", ""));
                    }
                    entries.add(new Entry(record, path.toFile(), false));
                }
            }
        } catch (Exception e) {
            throw new IOException("Datendatei: " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static Map<String, Pattern> compile(Map<String, String> fields) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!field.getValue().isEmpty()) patterns.put(field.getKey(),
                    Pattern.compile(field.getValue(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return patterns;
    }

    static List<Entry> search(List<Entry> entries, Map<String, Pattern> patterns) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries) {
            boolean matches = true;
            for (Map.Entry<String, Pattern> pattern : patterns.entrySet()) {
                String key = pattern.getKey();
                String[] targets = (key.equals("alias") || key.equals("comment"))
                        ? new String[]{"device", "alias", "comment"}
                        : (key.equals("category") || key.equals("device"))
                                ? new String[]{key, "comment"} : new String[]{key};
                boolean fieldMatches = false;
                for (String target : targets) {
                    if (pattern.getValue().matcher(entry.data.optString(target, "")).find()) {
                        fieldMatches = true;
                        break;
                    }
                }
                if (!fieldMatches) {
                    matches = false;
                    break;
                }
            }
            if (matches) found.add(entry);
        }
        return found;
    }

    static File imageFor(Entry hit, List<Entry> entries) {
        if (hit.image) return hit.source;
        String box = hit.data.optString("box", "");
        String device = hit.data.optString("device", "");
        if (box.isEmpty()) return null;
        for (Entry entry : entries) {
            if (entry.image && box.equalsIgnoreCase(entry.data.optString("box", ""))
                    && device.equals(entry.data.optString("device", ""))) return entry.source;
        }
        return null;
    }
}
