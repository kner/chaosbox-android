package local.sshcopy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class StoragePathsTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("category-storage-");
        try {
            StoragePaths paths = StoragePaths.parse(root.toFile(),
                    "[App.Chaosbox]\nJPG=ChaosBox/JPG\nDaten=ChaosBox/boxes\n");
            check(paths.data.equals(root.resolve("ChaosBox/boxes").toFile()), "Box root");
            check(paths.canUpload(new File(paths.data, "nested/box.json")), "ChaosBox upload allowed");
            check(!paths.canUpload(root.resolve("Bilderbox/boxes/box.json").toFile()), "Bilderbox upload excluded");
            check(!paths.canUpload(root.resolve("ChaosBox-other/box.json").toFile()), "Sibling prefix excluded");
            check(!paths.canUpload(root.resolve("ChaosBox/../outside.json").toFile()), "Path traversal excluded");
            Files.createDirectories(root.resolve("outside"));
            Files.createDirectories(root.resolve("ChaosBox"));
            Files.createSymbolicLink(root.resolve("ChaosBox/link"), root.resolve("outside"));
            check(!paths.canUpload(root.resolve("ChaosBox/link/box.json").toFile()), "Symlink escape excluded");
            check(StoragePaths.categoryFolder("Elektronik > Stromversorgung").equals("elektronik"), "Image category");
            Files.createDirectories(paths.images.toPath());
            Files.createDirectories(paths.data.toPath());
            byte[] picture = new byte[]{1, 2, 3};
            File image = new File(paths.images, "photo.jpg");
            Files.write(image.toPath(), picture);
            String json = "[{\"box\":\"a11\",\"device\":\"A\",\"category\":\"Elektronik>Sensoren\",\"anzahl\":2},"
                    + "{\"box\":\"a11\",\"device\":\"B\",\"category\":\"Audio\",\"anzahl\":7}]";
            File box = new File(paths.data, "a11.json");
            Files.write(box.toPath(), json.getBytes(StandardCharsets.UTF_8));
            File nested = new File(paths.data, "alt/gruppe/zweite.json");
            Files.createDirectories(nested.getParentFile().toPath());
            Files.write(nested.toPath(), "[{\"box\":\"zweite\"}]".getBytes(StandardCharsets.UTF_8));
            File deepImage = new File(paths.images, "elektronik/sensoren/temperatur/photo.jpg");
            Files.createDirectories(deepImage.getParentFile().toPath());
            Files.write(deepImage.toPath(), picture);
            Files.createSymbolicLink(new File(paths.images, "linked-directory").toPath(), deepImage.getParentFile().toPath());
            check(paths.imageFiles().size() == 2 && paths.imageFiles().contains(deepImage),
                    "Image picker and search discover arbitrary depth without following directory symlinks");
            check(paths.displayPath(deepImage).equals("JPG/elektronik/sensoren/temperatur/photo.jpg"),
                    "Image picker shows relative path");
            check(!paths.displayPath(image).equals(paths.displayPath(deepImage)), "Duplicate basenames distinguishable");
            check(paths.migrate(file -> "Audio").isEmpty(), "Image migration warnings");
            check(deepImage.isFile(), "Existing nested image organization preserved");
            check(Arrays.equals(picture, Files.readAllBytes(new File(paths.images, "audio/photo.jpg").toPath())),
                    "Image bytes unchanged");
            check(LocalData.read(box).equals(json), "a11.json remains in boxes with both records");
            check(paths.jsonFiles().contains(nested), "Nested box discovered recursively");
            check(paths.findBox("a11", "Elektronik").equals(box), "Box lookup ignores category");
            check(paths.findBox("A11", "").equals(box), "Uppercase box lookup");
            check(paths.findBox("A11.JSON", "").equals(box), "Uppercase extension lookup");
            BoxRecords.save(box.getParentFile(), box.getName(), "{\"device\":\"B\",\"anzahl\":9}", 1);
            check(BoxRecords.load(box.getParentFile(), box.getName()).size() == 2,
                    "Count update preserves record count");
            check(box.isFile(), "Count update stays in boxes root");
            File formerData = new File(paths.legacyData, "elektronik/frueher.json");
            Files.createDirectories(formerData.getParentFile().toPath());
            Files.write(formerData.toPath(), "[{\"device\":\"C\"}]".getBytes(StandardCharsets.UTF_8));
            check(paths.jsonFiles().contains(formerData), "Previously sorted box remains readable");
            check(paths.migrate(file -> "Audio").isEmpty(), "No JSON migration warnings");
            check(formerData.isFile(), "Previously sorted box never moved");
            try {
                StoragePaths.parse(root.toFile(),
                        "[App.Chaosbox]\nJPG=ChaosBox/JPG\nDaten=ChaosBox/data\n");
                throw new AssertionError("Index mixed with source records");
            } catch (java.io.IOException expected) { }
            System.out.println("PASS: image categories, unmoved boxes, nested lookup, existing records");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
            }
        }
    }
}
