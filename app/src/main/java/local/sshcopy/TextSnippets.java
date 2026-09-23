package local.sshcopy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named clipboard text, in setup order, without an application-defined count limit. */
final class TextSnippets {
    static final String DEFAULT_SECTION = "\n[TextSnippets]\ntext1=\"text1\"\ntext2=\"text2\"\n";

    static Map<String, String> parse(String text) {
        Map<String, String> snippets = new LinkedHashMap<>();
        boolean active = false;
        for (String raw : text.replace("\uFEFF", "").split("\\r?\\n")) {
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
