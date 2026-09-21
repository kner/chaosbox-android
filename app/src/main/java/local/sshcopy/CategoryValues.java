package local.sshcopy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

/** Category labels for the editor, in source order and without duplicate roots. */
final class CategoryValues {
    static String read(String raw) throws JSONException {
        Set<String> roots = new LinkedHashSet<>();
        JSONTokener parser = new JSONTokener(raw);
        readContainer(parser, roots);
        if (parser.nextClean() != 0) throw new JSONException("Zusätzlicher JSON-Inhalt");
        return String.join(", ", roots);
    }

    private static void readContainer(JSONTokener parser, Set<String> roots) throws JSONException {
        char start = parser.nextClean();
        if (start == '[') {
            if (parser.nextClean() == ']') return;
            parser.back();
            while (true) {
                readContainer(parser, roots);
                char separator = parser.nextClean();
                if (separator == ']') return;
                if (separator != ',') throw new JSONException("Ungültige JSON-Liste");
            }
        }
        if (start != '{') throw new JSONException("JSON-Objekt erwartet");
        if (parser.nextClean() == '}') return;
        parser.back();
        while (true) {
            Object key = parser.nextValue();
            if (!(key instanceof String) || parser.nextClean() != ':')
                throw new JSONException("Ungültiger JSON-Schlüssel");
            Object value = parser.nextValue();
            if ("category".equalsIgnoreCase((String) key)
                    || "kategorie".equalsIgnoreCase((String) key)) add(roots, value);
            char separator = parser.nextClean();
            if (separator == '}') break;
            if (separator != ',') throw new JSONException("Ungültiges JSON-Objekt");
        }
    }

    private static void add(Set<String> roots, Object value) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            for (int i = 0; i < values.length(); i++) add(roots, values.get(i));
        } else if (value instanceof String) {
            for (String path : ((String) value).split("[,;|\r\n]+")) {
                int divider = path.indexOf('>');
                String root = (divider < 0 ? path : path.substring(0, divider)).trim();
                if (!root.isEmpty()) roots.add(root);
            }
        }
    }

    private CategoryValues() { }
}
