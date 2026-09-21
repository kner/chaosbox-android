package local.sshcopy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONException;

final class BoxRecords {
    static void validateName(String name) throws IOException {
        if (name.trim().isEmpty() || name.contains("/") || name.contains("\\")
                || name.matches(".*[\\p{Cntrl}].*")) {
            throw new IOException("Box muss einen gültigen Dateinamen enthalten (keine Schrägstriche oder Steuerzeichen).");
        }
    }

    static java.util.List<JSONObject> parseRecords(String text) throws IOException {
        try {
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            if (parser.nextClean() != 0) throw new JSONException("Zusätzlicher Inhalt");
            java.util.List<JSONObject> records = new java.util.ArrayList<>();
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) records.add(array.getJSONObject(i));
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                if (object.has("device") || object.has("box") || object.has("comment")
                        || object.has("anzahl") || object.has("category") || object.has("alias")
                        || object.has("package") || object.has("count") || object.has("pack")
                        || object.has("created") || object.has("modified")) {
                    records.add(object);
                } else {
                    java.util.Iterator<String> keys = object.keys();
                    while (keys.hasNext()) records.add(object.getJSONObject(keys.next()));
                }
            } else {
                throw new JSONException("Objekt oder Array erwartet");
            }
            if (records.isEmpty()) throw new JSONException("Keine Datensätze vorhanden");
            return records;
        } catch (JSONException e) {
            throw new IOException("Ungültige JSON-Datensätze: " + e.getMessage(), e);
        }
    }

    static synchronized File save(File directory, String name, String json) throws IOException {
        validateName(name);
        Files.createDirectories(directory.toPath());
        Path target = directory.toPath().resolve(name + ".json");
        if (Files.isSymbolicLink(target)) throw new IOException("Box-Datei darf kein symbolischer Link sein.");
        JSONArray records = new JSONArray();
        try {
            if (Files.exists(target)) {
                String existing = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
                if (existing.startsWith("\uFEFF")) existing = existing.substring(1);
                JSONTokener parser = new JSONTokener(existing);
                Object value = parser.nextValue();
                if (parser.nextClean() != 0) throw new JSONException("Zusätzlicher Inhalt");
                if (value instanceof JSONArray) records = (JSONArray) value;
                else if (value instanceof JSONObject) records.put(value);
                else throw new JSONException("Objekt oder Array erwartet");
            }
            records.put(new JSONObject(json));
            Path temporary = Files.createTempFile(directory.toPath(), ".box-", ".tmp");
            try {
                Files.write(temporary, records.toString(2).getBytes(StandardCharsets.UTF_8));
                // Fail safely if this filesystem cannot replace the complete file atomically.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (JSONException e) {
            throw new IOException("Ungültige JSON-Daten; bestehende Box-Datei bleibt unverändert.", e);
        }
        return target.toFile();
    }
}
