package local.sshcopy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONException;

final class LocalData {
    static String read(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) result.append(buffer, 0, n);
            if (result.length() > 0 && result.charAt(0) == '\uFEFF') result.deleteCharAt(0);
            return result.toString();
        }
    }

    static List<String> records(String text) throws JSONException {
        JSONTokener parser = new JSONTokener(text);
        Object value = parser.nextValue();
        if (parser.nextClean() != 0) throw new JSONException("Additional content after JSON");
        List<String> records = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) records.add(format(array.get(i)));
        } else if (value instanceof JSONObject) {
            records.add(format(value));
        } else {
            throw new JSONException("JSON-Objekt oder Array erwartet");
        }
        return records;
    }

    private static String format(Object value) throws JSONException {
        if (value instanceof JSONObject) return ((JSONObject) value).toString(2);
        if (value instanceof JSONArray) return ((JSONArray) value).toString(2);
        return String.valueOf(value);
    }
}
