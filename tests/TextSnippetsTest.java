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
        System.out.println("PASS: snippet parsing, 10000 entries, setup migration, existing/empty sections");
    }
}
