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

    static java.util.List<JSONObject> load(File directory, String name) throws IOException {
        validateName(name);
        File file = new File(directory, name.endsWith(".json") ? name : name + ".json");
        return parseRecords(LocalData.read(file));
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
        return save(directory, name, json, -1);
    }

    static synchronized File save(File directory, String name, String json, int selectedIndex) throws IOException {
        validateName(name);
        Files.createDirectories(directory.toPath());
        Path target = directory.toPath().resolve(name.endsWith(".json") ? name : name + ".json");
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
                else if (value instanceof JSONObject) {
                    JSONObject object = (JSONObject) value;
                    boolean grouped = object.length() > 0;
                    java.util.Iterator<String> keys = object.keys();
                    while (keys.hasNext()) {
                        if (!(object.get(keys.next()) instanceof JSONObject)) grouped = false;
                    }
                    if (grouped) {
                        for (JSONObject record : parseRecords(existing)) records.put(record);
                    } else records.put(object);
                }
                else throw new JSONException("Objekt oder Array erwartet");
            }
            JSONObject incoming = new JSONObject(json);
            String device = incoming.optString("device", "");
            int match = -1;
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                if (device.equals(record.optString("device", ""))) {
                    if (match < 0 || i == selectedIndex) match = i;
                }
            }
            if (match >= 0) {
                JSONObject existing = records.getJSONObject(match);
                java.util.Iterator<String> keys = incoming.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.equals("created") && !existing.optString("created", "").isEmpty()) continue;
                    existing.put(key, incoming.get(key));
                }
                // Imported count/pack fields take precedence when reading; keep aliases consistent.
                if (incoming.has("count") || incoming.has("anzahl")) {
                    Object amount = incoming.has("count") ? incoming.get("count") : incoming.get("anzahl");
                    if (existing.has("count")) existing.put("count", amount);
                    if (existing.has("anzahl")) existing.put("anzahl", amount);
                }
                if (incoming.has("pack") || incoming.has("package")) {
                    Object pack = incoming.has("pack") ? incoming.get("pack") : incoming.get("package");
                    if (existing.has("pack")) existing.put("pack", pack);
                    if (existing.has("package")) existing.put("package", pack);
                }
            } else records.put(incoming);
            Path temporary = Files.createTempFile(directory.toPath(), ".box-", ".tmp");
            try {
                Files.write(temporary, records.toString(2).getBytes(StandardCharsets.UTF_8));
                if (Files.exists(target)) {
                    long modified = Math.max(System.currentTimeMillis(),
                            (Files.getLastModifiedTime(target).toMillis() / 1000 + 1) * 1000);
                    Files.setLastModifiedTime(temporary, java.nio.file.attribute.FileTime.fromMillis(modified));
                }
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
