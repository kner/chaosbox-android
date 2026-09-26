package local.sshcopy;

import java.io.IOException;
import java.nio.file.*;

/** Source-only regression checks; run explicitly when builds are permitted. */
public final class AppProfilesTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String text = "[App]\nStandard=Bilderbox\n"
                + "[App.Chaobox]\nTitel=Chaosbox\nJPG=ChaosBox/JPG\nDaten=ChaosBox/daten\n"
                + "[App.Bilderbox]\nTitel=Bilderbox\nJPG=Bilderbox/JPG\nDaten=Bilderbox/daten\n"
                + "Felder=Box,,,Tags,Kategorie,Kommentar\nKategorie=Fotos, Gemälde, Fotos\n"
                + "[App.Archiv]\nTitel=Archiv\nBilder=Archiv/JPG\nDaten=Archiv/daten\nFelder=\n";
        AppProfiles profiles = AppProfiles.parse(text);
        check(profiles.imageLimit == 3000, "Default image side limit");
        check(AppProfiles.parse(text + "\n[ImageSize]\nLIMIT=4096\n").imageLimit == 4096,
                "Configured image side limit");
        try {
            AppProfiles.parse(text + "\n[ImageSize]\nLIMIT=0\n");
            throw new AssertionError("Invalid image size accepted");
        } catch (IOException expected) { }
        check(profiles.find("Bilderbox").categories.equals(java.util.Arrays.asList("Fotos", "Gemälde")),
                "Profile categories are trimmed and duplicates removed");
        check(profiles.find("Chaobox").categories.isEmpty(), "Other profile has no shared categories");
        check(profiles.profiles.size() == 3, "Arbitrary profile sections");
        check(profiles.selected("").id.equals("Bilderbox"), "Configured default");
        check(profiles.selected("Chaobox").title.equals("Chaosbox"), "Remembered selection overrides default");
        check(profiles.selected("deleted").id.equals("Bilderbox"), "Deleted selection falls back");
        AppProfiles.Profile pictures = profiles.find("Bilderbox");
        check(pictures.visible(0) && !pictures.visible(1) && !pictures.visible(2), "Empty inner positions hidden");
        check(pictures.fields[3].equals("Tags") && pictures.visible(4) && pictures.visible(5), "Stable field positions");
        check(pictures.fields[4].equals("Category") && pictures.fields[5].equals("Comment"),
                "Legacy field labels appear in English");
        check(profiles.find("Chaobox").fields[1].equals("Quantity"), "English default quantity label");
        check(!pictures.visible(6), "Omitted trailing position hidden");
        for (int i = 0; i < 7; i++) {
            check(profiles.find("Chaobox").visible(i), "Missing Felder uses defaults");
            check(!profiles.find("Archiv").visible(i), "Empty Felder hides all fields");
        }
        String extended = AppProfiles.withCategory(text, "bilderbox", "  Keramik, fotos, KERAMIK  ");
        check(AppProfiles.parse(extended).find("Bilderbox").categories.equals(
                java.util.Arrays.asList("Fotos", "Gemälde", "Keramik")), "Append new categories once, ignoring case");
        check(extended.replace(
                "Kategorie=Fotos, Gemälde, Fotos, Keramik", "Kategorie=Fotos, Gemälde, Fotos").equals(text),
                "Only the selected category line changes");
        check(AppProfiles.withCategory(extended, "Bilderbox", "keramik").equals(extended), "Repeated save is unchanged");
        check(AppProfiles.withCategory(text, "Bilderbox", "  , ").equals(text), "Ignore empty category");
        String missing = AppProfiles.withCategory(text, "Chaobox", "Elektro");
        check(AppProfiles.parse(missing).find("Chaobox").categories.equals(java.util.List.of("Elektro")),
                "Insert missing category key before next profile");
        check(AppProfiles.parse(AppProfiles.withCategory(text, "Archiv", "Ende")).find("Archiv")
                .categories.equals(java.util.List.of("Ende")), "Insert category at end of file");
        String windows = text.replace("\n", "\r\n");
        check(AppProfiles.withCategory(windows, "Bilderbox", "Keramik").replace(
                "Kategorie=Fotos, Gemälde, Fotos, Keramik", "Kategorie=Fotos, Gemälde, Fotos").equals(windows),
                "Preserve CRLF and unrelated setup content");
        String inherited = "[Kategorie]\nAudio\n" + text;
        check(AppProfiles.parse(AppProfiles.withCategory(inherited, "Chaobox", "Video"))
                .find("Chaobox").categories.equals(java.util.List.of("Audio", "Video")), "Preserve inherited categories");
        check(AppProfiles.parse(AppProfiles.withCategory("[Kategorie]\nAudio\n", "Chaosbox", "Video"))
                .find("Chaosbox").categories.equals(java.util.List.of("Audio", "Video")), "Extend legacy setup");
        try {
            AppProfiles.withCategory(text, "Bilderbox", "Bad\n[App.Other]");
            throw new AssertionError("Multiline category accepted");
        } catch (IOException expected) { }
        String completed = AppProfiles.withDefaults(text);
        check(completed.contains("Daten=ChaosBox/boxes"), "Older Chaosbox data path updated");
        check(completed.contains("Daten=Bilderbox/boxes"), "Older Bilderbox data path updated");
        check(completed.contains("Kategorie=Fotos, Gemälde, Fotos"), "Existing profile category retained");
        check(completed.contains("[App.Archiv]"), "Other profile retained");
        check(AppProfiles.withDefaults(completed).equals(completed), "Existing profiles extended once");
        String converted = AppProfiles.withDefaults("[Kategorie]\nAudio\n[Pfade]\nBilder=Photos\nDaten=Records\n");
        check(!converted.contains("[Kategorie]"), "Remove global category section");
        check(AppProfiles.parse(converted).find("Chaosbox").categories.equals(java.util.Collections.singletonList("Audio")),
                "Move old category to profile");
        check(!converted.contains("[Pfade]"), "Convert old section");
        check(AppProfiles.parse(converted).find("Chaosbox").images.equals("Photos"), "Preserve old paths");
        check(AppProfiles.withDefaults(converted).equals(converted), "Idempotent conversion");
        String migrated = AppProfiles.withDefaults("[Kategorie]\nAudio\nElektro\n" + text);
        check(!migrated.contains("[Kategorie]"), "Remove old global list from profiled setup");
        check(AppProfiles.parse(migrated).find("Chaobox").categories.size() == 2,
                "Copy old categories to profile lacking a list");
        check(AppProfiles.parse(migrated).find("Bilderbox").categories.equals(java.util.Arrays.asList("Fotos", "Gemälde")),
                "Do not replace profile's own list");
        try {
            AppProfiles.parse("[App.Bad]\nJPG=Photos\n");
            throw new AssertionError("Missing data path accepted");
        } catch (IOException expected) { }
        Path root = Files.createTempDirectory("app-profiles-");
        try {
            StoragePaths original = StoragePaths.forProfile(root.toFile(), profiles.find("Chaobox"));
            StoragePaths other = StoragePaths.forProfile(root.toFile(), pictures);
            check(!original.index.equals(other.index), "Separate indices");
            check(other.legacyData.equals(root.resolve("Bilderbox/daten").toFile().getCanonicalFile()),
                    "Bilderbox reads only its own legacy data folder");
            check(other.data.equals(root.resolve("Bilderbox/boxes").toFile().getCanonicalFile()),
                    "Bilderbox uses its migrated boxes folder");
            check(other.legacyImages.equals(other.images), "Other profiles never import Chaosbox images");
            StoragePaths snapshot = StoragePaths.snapshot(root.toFile(), other.images.getPath(), other.data.getPath(), pictures.id);
            check(snapshot.index.equals(other.index), "Upload snapshot preserves profile index");
        } finally { Files.delete(root); }
        System.out.println("PASS: profiles, selection, field positions, legacy setup, storage isolation");
    }
}
