package local.sshcopy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Storage layout for one selected App profile. */
final class StoragePaths {
    final File images, data, legacyImages, legacyData, index;

    private StoragePaths(File storage, String imagePath, String dataPath, String profileId) throws IOException {
        images = resolve(storage, imagePath);
        File configuredData = resolve(storage, dataPath);
        File oldImages = new File(storage, "ChaosBox/JPG").getCanonicalFile();
        File oldData = new File(storage, "ChaosBox/daten").getCanonicalFile();
        File boxes = new File(storage, "ChaosBox/boxes").getCanonicalFile();
        File pictureImages = new File(storage, "Bilderbox/JPG").getCanonicalFile();
        File pictureData = new File(storage, "Bilderbox/daten").getCanonicalFile();
        File pictureBoxes = new File(storage, "Bilderbox/boxes").getCanonicalFile();
        boolean chaos = (profileId.equalsIgnoreCase("Chaobox") || profileId.equalsIgnoreCase("Chaosbox"))
                && images.equals(oldImages);
        boolean pictures = profileId.equalsIgnoreCase("Bilderbox") && images.equals(pictureImages);
        data = chaos && configuredData.equals(oldData) ? boxes
                : pictures && configuredData.equals(pictureData) ? pictureBoxes : configuredData;
        legacyImages = images;
        // Previously sorted JSON remains readable where it is; never move it again.
        legacyData = chaos && data.equals(boxes) ? oldData
                : pictures && data.equals(pictureBoxes) ? pictureData : data;
        index = chaos && data.equals(boxes) ? new File(storage, "ChaosBox/data").getCanonicalFile()
                : new File(storage, "ChaosBox/.indices/" + profileKey(profileId)).getCanonicalFile();
        for (String reserved : new String[]{"ChaosBox/data", "ChaosBox/.indices", "ChaosBox/Setup"}) {
            File directory = new File(storage, reserved).getCanonicalFile();
            if (overlaps(images, directory) || overlaps(data, directory))
                throw new IOException("Bild- und Datenordner überschneiden sich mit einem internen App-Ordner: " + directory);
        }
        if (overlaps(images, data) || overlaps(images, index) || overlaps(data, index)
                || overlaps(images, legacyData) || overlaps(data, legacyImages))
            throw new IOException("Bild-, Daten- und Indexordner müssen getrennt sein.");
    }

    private static boolean overlaps(File a, File b) {
        return a.toPath().startsWith(b.toPath()) || b.toPath().startsWith(a.toPath());
    }

    private static File resolve(File storage, String value) throws IOException {
        File file = new File(value);
        return (file.isAbsolute() ? file : new File(storage, value)).getCanonicalFile();
    }

    static StoragePaths forProfile(File storage, AppProfiles.Profile profile) throws IOException {
        return new StoragePaths(storage, profile.images, profile.data, profile.id);
    }

    static StoragePaths snapshot(File storage, String images, String data, String id) throws IOException {
        return new StoragePaths(storage, images, data, id);
    }

    private static String profileKey(String id) {
        return java.util.UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // Pure parser retained for configuration checks and standalone regression tests.
    static StoragePaths parse(File storage, String text) throws IOException {
        AppProfiles config = AppProfiles.parse(text);
        return forProfile(storage, config.selected(""));
    }

    static String withDefaults(String text) throws IOException {
        return AppProfiles.withDefaults(text);
    }

    static String categoryFolder(String category) {
        String first = category == null ? "" : category.split("[,;|\\r\\n]", -1)[0];
        int level = first.indexOf('>');
        if (level >= 0) first = first.substring(0, level);
        first = first.trim().toLowerCase(Locale.ROOT);
        if (first.isEmpty() || first.equals("ohne kategorie") || first.equals(".") || first.equals(".."))
            return "unassigned";
        return first.replaceAll("[/\\\\\\p{Cntrl}]", "_");
    }

    File categoryDirectory(boolean image, String category) throws IOException {
        if (!image) return data;
        File root = images;
        File child = new File(root, categoryFolder(category));
        if (!child.getCanonicalFile().getParentFile().equals(root) || Files.isSymbolicLink(child.toPath()))
            throw new IOException("Ungültiger Kategorieordner: " + child);
        return child;
    }

    static List<File> files(File root, boolean image) throws IOException {
        List<File> result = new ArrayList<>();
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return result;
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Kein Datenordner: " + root);
        try (Stream<Path> paths = Files.walk(root.toPath(), image ? 2 : Integer.MAX_VALUE)) {
            paths.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(f -> {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (image ? name.endsWith(".jpg") || name.endsWith(".jpeg") : name.endsWith(".json"))
                    result.add(f.toFile());
            });
        }
        return result;
    }

    List<File> imageFiles() throws IOException {
        Set<File> all = new LinkedHashSet<>(files(images, true));
        all.addAll(files(legacyImages, true));
        return new ArrayList<>(all);
    }

    List<File> jsonFiles() throws IOException {
        Set<File> all = new LinkedHashSet<>(files(data, false));
        all.addAll(files(legacyData, false));
        return new ArrayList<>(all);
    }

    interface ImageCategory { String read(File image) throws Exception; }

    synchronized List<String> migrate(ImageCategory reader) throws IOException {
        List<String> warnings = new ArrayList<>();
        for (File image : imageFiles()) {
            try { moveToCategory(image, true, reader.read(image)); }
            catch (Exception e) { warnings.add(image.getName() + ": " + e.getMessage()); }
        }
        return warnings;
    }

    private void moveToCategory(File source, boolean image, String category) throws IOException {
        File directory = categoryDirectory(image, category);
        File target = new File(directory, source.getName());
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) return;
        Files.createDirectories(directory.toPath());
        // No REPLACE_EXISTING: a collision must leave both files intact.
        Files.move(source.toPath(), target.toPath());
    }

    File findBox(String name, String category) throws IOException {
        BoxRecords.validateName(name);
        String filename = name.endsWith(".json") ? name : name + ".json";
        File preferred = new File(data, filename);
        List<File> matches = new ArrayList<>();
        for (File file : jsonFiles()) if (file.getName().equals(filename)) matches.add(file);
        if (matches.contains(preferred)) return preferred;
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) throw new IOException("Box mehrfach vorhanden. Bitte Box leeren und über die Dateiauswahl öffnen.");
        throw new IOException("Box nicht gefunden: " + filename);
    }
}
