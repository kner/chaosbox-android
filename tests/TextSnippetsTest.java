package local.sshcopy;

import java.util.*;

public final class TextSnippetsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String setup = "\uFEFF[Other]\nignored=other\n[ textSNIPPETS ]\r\n"
                + "# comment\r\n; comment\r\nText1=\"Grüße = schön; # bleibt\"\r\n"
                + "text2=\"  Abstand  \"\r\nplain=ohne Anführungszeichen\r\n"
                + "empty=\"\"\r\npath=\"C:\\temp\\neu\"\r\n"
                + "=ignored\r\ninvalid line\r\n[Next]\r\nother=ignored\r\n";
        Map<String, String> snippets = TextSnippets.parse(setup);
        check(snippets.size() == 5, "Only named entries in TextSnippets");
        check(new ArrayList<>(snippets.keySet()).equals(Arrays.asList("Text1", "text2", "plain", "empty", "path")),
                "Preserve names and setup order");
        check(snippets.get("Text1").equals("Grüße = schön; # bleibt"), "Preserve embedded punctuation and Unicode");
        check(snippets.get("text2").equals("  Abstand  "), "Preserve quoted spaces");
        check(snippets.get("plain").equals("ohne Anführungszeichen"), "Allow unquoted values");
        check(snippets.get("empty").isEmpty(), "Allow empty text");
        check(snippets.get("path").equals("C:\\temp\\neu"), "Do not interpret backslashes");
        String body = "Erste Zeile  \n  Zweite Zeile\n\n# Text\n; Text\n"
                + "[App.Fake]\nDaten=ChaosBox/daten\n[Kategorie]\nAudio\n"
                + "[ImageSize]\nLIMIT=0\nBei Unsicherheit schreibe \"?\"\nLetzte Zeile";
        String multiline = "[TextSnippets]\nlang=\"" + body + "\"\n"
                + "next=\"Danach\"\nseparat=\"Start\nEnde\n\"\n";
        Map<String, String> multi = TextSnippets.parse(multiline.replace("\n", "\r\n"));
        check(multi.get("lang").equals(body), "Preserve newlines, spaces, comments, sections and paired quotes");
        check(multi.get("next").equals("Danach"), "Read next entry after multiline value");
        check(multi.get("separat").equals("Start\nEnde\n"), "Closing quote on separate line");
        AppProfiles parsed = AppProfiles.parse(multiline);
        check(parsed.imageLimit == 3000 && parsed.find("Fake") == null,
                "Snippet content does not change configuration sections");
        String completed = AppProfiles.withDefaults(multiline);
        check(AppProfiles.parse(completed).textSnippets.equals(multi), "Migration preserves complete multiline values");
        check(!AppProfiles.parse(completed).profiles.get(0).categories.contains("Audio"),
                "Snippet content is not migrated as legacy categories");
        check(AppProfiles.withDefaults(completed).equals(completed), "Multiline migration is idempotent");
        try {
            AppProfiles.withDefaults("[TextSnippets]\nbroken=\"Erste Zeile\nWeitere Zeile");
            throw new AssertionError("Unterminated snippet accepted");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("broken"), "Name malformed snippet in error");
        }
        String bundled = java.nio.file.Files.readString(java.nio.file.Path.of(
                "app/src/main/assets/initial/Setup/setup.ini"));
        Map<String, String> bundledSnippets = TextSnippets.parse(bundled);
        check(bundledSnippets.equals(AppProfiles.parse(AppProfiles.withDefaults(bundled)).textSnippets),
                "Bundled snippets survive setup migration");
        check(bundledSnippets.get("chatgpt").contains("schreibe  \"?\"\n- Beschreibe"),
                "Paired quotes at line end do not end bundled chatgpt snippet");
        check(bundledSnippets.get("gemini").endsWith("Unbekannte Teile mit ??? kennzeichnen."),
                "Read bundled gemini snippet through its last line");
        StringBuilder many = new StringBuilder("[TextSnippets]\n");
        for (int i = 0; i < 10000; i++) many.append("text").append(i).append("=\"Inhalt ").append(i).append("\"\n");
        check(TextSnippets.parse(many.toString()).size() == 10000, "No fixed entry limit");
        String migrated = AppProfiles.withDefaults("[ImageSize]\nLIMIT=2048\n");
        check(AppProfiles.parse(migrated).textSnippets.equals(TextSnippets.parse(TextSnippets.DEFAULT_SECTION)),
                "Add both examples to old setups");
        check(AppProfiles.parse(migrated).imageLimit == 2048, "Preserve image limit");
        check(AppProfiles.withDefaults(migrated).equals(migrated), "Migration is idempotent");
        check(AppProfiles.parse(AppProfiles.withDefaults(setup)).textSnippets.equals(snippets),
                "Preserve existing user snippets without adding examples");
        check(AppProfiles.parse(AppProfiles.withDefaults("[TextSnippets]\n")).textSnippets.isEmpty(),
                "Keep intentionally empty section empty");
        System.out.println("PASS: single/multiline snippets, bundled prompts, 10000 entries, migration, malformed quotes");
    }
}
