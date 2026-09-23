package local.sshcopy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named clipboard text, in setup order, without an application-defined count limit. */
final class TextSnippets {
    static final String DEFAULT_SECTION = "\n[TextSnippets]\ntext1=\"text1\"\ntext2=\"text2\"\n";

    // Keep quoted snippet values together for every reader, including setup migration.
    static List<String> lines(String text) throws IOException {
        List<String> lines = new ArrayList<>();
        boolean active = false;
        StringBuilder pending = null;
        String name = null;
        for (String raw : text.replace("\uFEFF", "").split("\\r?\\n", -1)) {
            if (pending != null) {
                pending.append('\n').append(raw);
                if (oddQuotes(raw)) {
                    if (!raw.trim().endsWith("\""))
                        throw new IOException("Textbaustein " + name + ": Schlusszeichen muss am Zeilenende stehen.");
                    lines.add(pending.toString());
                    pending = null;
                }
                continue;
            }
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                active = line.substring(1, line.length() - 1).trim().equalsIgnoreCase("TextSnippets");
            } else if (active && !line.startsWith("#") && !line.startsWith(";")) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    String value = line.substring(equals + 1).trim();
                    if (value.startsWith("\"") && oddQuotes(value)) {
                        pending = new StringBuilder(raw);
                        name = line.substring(0, equals).trim();
                        continue;
                    }
                }
            }
            lines.add(raw);
        }
        if (pending != null)
            throw new IOException("Textbaustein " + name + ": Schließendes Anführungszeichen fehlt.");
        return lines;
    }

    private static boolean oddQuotes(String text) {
        boolean odd = false;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '"') odd = !odd;
        return odd;
    }

    static Map<String, String> parse(String text) throws IOException {
        Map<String, String> snippets = new LinkedHashMap<>();
        boolean active = false;
        for (String raw : lines(text)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                active = line.substring(1, line.length() - 1).trim().equalsIgnoreCase("TextSnippets");
                continue;
            }
            if (!active || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String name = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                value = value.substring(1, value.length() - 1);
            if (!name.isEmpty()) snippets.put(name, value);
        }
        return Collections.unmodifiableMap(snippets);
    }
}
