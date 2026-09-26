package local.sshcopy;

import java.io.IOException;
import java.util.*;

/** INI profiles; field positions always retain their original data meaning. */
final class AppProfiles {
    static final String DEFAULT_FIELDS = "Box,Quantity,Device,Alias,Category,Comment,Package";
    // Translate legacy UI labels while retaining custom labels and storage keys.
    private static String englishLabel(String label) {
        if (label.equalsIgnoreCase("Anzahl")) return "Quantity";
        if (label.equalsIgnoreCase("Kategorie")) return "Category";
        if (label.equalsIgnoreCase("Kommentar")) return "Comment";
        return label;
    }

    static final class Profile {
        final String id, title, images, data;
        final String[] fields;
        final List<String> categories;
        Profile(String id, Map<String, String> values) throws IOException {
            this.id = id;
            title = values.getOrDefault("titel", id.equalsIgnoreCase("Chaobox") ? "Chaosbox" : id).trim();
            images = values.getOrDefault("bilder", values.getOrDefault("jpg", "")).trim();
            data = values.getOrDefault("daten", "").trim();
            if (title.isEmpty() || images.isEmpty() || data.isEmpty())
                throw new IOException("App." + id + ": Titel, Bilder/JPG and Daten must not be empty.");
            String[] labels = values.getOrDefault("felder", DEFAULT_FIELDS).split(",", -1);
            if (labels.length > 7) throw new IOException("App." + id + ": Felder has more than seven positions.");
            fields = new String[7];
            for (int i = 0; i < 7; i++) fields[i] = i < labels.length ? englishLabel(labels[i].trim()) : "";
            categories = Collections.unmodifiableList(splitCategories(values.getOrDefault("kategorie", "")));
        }
        boolean visible(int position) { return !fields[position].isEmpty(); }
        String signature() { return id + "\n" + title + "\n" + images + "\n" + data + "\n" + String.join(",", fields); }
    }

    final List<Profile> profiles;
    final String defaultId;
    final int imageLimit;
    final Map<String, String> textSnippets;
    private AppProfiles(List<Profile> profiles, String requestedDefault, int imageLimit, Map<String, String> textSnippets) throws IOException {
        this.imageLimit = imageLimit;
        this.textSnippets = textSnippets;
        this.profiles = Collections.unmodifiableList(profiles);
        if (profiles.isEmpty()) throw new IOException("No [App.Name] sections found.");
        defaultId = requestedDefault.isEmpty() ? profiles.get(0).id : requestedDefault;
        if (find(defaultId) == null) throw new IOException("Unknown default profile: " + defaultId);
    }
    Profile find(String id) {
        for (Profile profile : profiles) if (profile.id.equalsIgnoreCase(id)) return profile;
        return null;
    }
    Profile selected(String id) { Profile p = find(id); return p == null ? find(defaultId) : p; }

    static AppProfiles parse(String text) throws IOException {
        Map<String, Map<String, String>> sections = sections(text);
        List<Profile> profiles = new ArrayList<>();
        String oldCategories = legacyCategoryValue(text);
        String defaultId = "";
        for (Map.Entry<String, Map<String, String>> entry : sections.entrySet()) {
            String section = entry.getKey();
            if (section.equalsIgnoreCase("App")) defaultId = entry.getValue().getOrDefault("standard", "");
            if (section.regionMatches(true, 0, "App.", 0, 4)) {
                String id = section.substring(4).trim();
                if (id.isEmpty()) throw new IOException("Empty app profile name.");
                for (Profile old : profiles) if (old.id.equalsIgnoreCase(id))
                    throw new IOException("Duplicate app profile: " + id);
                Map<String, String> values = new LinkedHashMap<>(entry.getValue());
                values.putIfAbsent("kategorie", oldCategories);
                profiles.add(new Profile(id, values));
            }
        }
        if (profiles.isEmpty()) {
            Map<String, String> legacy = new LinkedHashMap<>();
            legacy.put("bilder", "ChaosBox/JPG");
            legacy.put("daten", "ChaosBox/daten");
            for (Map.Entry<String, Map<String, String>> entry : sections.entrySet())
                if (entry.getKey().equalsIgnoreCase("Pfade")) legacy.putAll(entry.getValue());
            legacy.put("kategorie", oldCategories);
            profiles.add(new Profile("Chaosbox", legacy));
        }
        return new AppProfiles(profiles, defaultId, imageLimit(sections), TextSnippets.parse(text));
    }

    private static int imageLimit(Map<String, Map<String, String>> sections) throws IOException {
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            if (!section.getKey().equalsIgnoreCase("ImageSize")) continue;
            String value = section.getValue().get("limit");
            if (value == null) return 3000;
            try {
                int pixels = Integer.parseInt(value);
                if (pixels > 0 && pixels <= 20000) return pixels;
            } catch (NumberFormatException ignored) { }
            throw new IOException("ImageSize: LIMIT must be a pixel count from 1 to 20000.");
        }
        return 3000;
    }

    private static Map<String, Map<String, String>> sections(String text) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> values = null;
        for (String raw : TextSnippets.lines(text)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                values = sections.computeIfAbsent(line.substring(1, line.length() - 1).trim(), k -> new LinkedHashMap<>());
            } else if (values != null && !line.startsWith("#") && !line.startsWith(";")) {
                int equals = line.indexOf('=');
                if (equals >= 0) values.put(line.substring(0, equals).trim().toLowerCase(Locale.ROOT), line.substring(equals + 1).trim());
            }
        }
        return sections;
    }

    private static List<String> splitCategories(String value) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : value.split(",", -1)) {
            String category = raw.trim();
            if (!category.isEmpty()) unique.putIfAbsent(category.toLowerCase(Locale.ROOT), category);
        }
        return new ArrayList<>(unique.values());
    }

    private static String legacyCategoryValue(String text) throws IOException {
        boolean oldCategorySection = false;
        List<String> entries = new ArrayList<>();
        for (String raw : TextSnippets.lines(text)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                oldCategorySection = line.equalsIgnoreCase("[Kategorie]");
            } else if (oldCategorySection && !line.isEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                if (line.regionMatches(true, 0, "Kategorie=", 0, 10)) line = line.substring(10).trim();
                entries.add(line);
            }
        }
        return String.join(", ", splitCategories(String.join(",", entries)));
    }

    /** Append new category labels only to the selected profile's INI list. */
    static String withCategory(String text, String profileId, String entered) throws IOException {
        if (entered.indexOf('\n') >= 0 || entered.indexOf('\r') >= 0)
            throw new IOException("Category must be a single line.");
        Profile profile = parse(text).find(profileId);
        if (profile == null) throw new IOException("App profile not found: " + profileId);
        List<String> additions = new ArrayList<>();
        for (String candidate : splitCategories(entered)) {
            boolean known = false;
            for (String existing : profile.categories)
                if (existing.equalsIgnoreCase(candidate)) known = true;
            if (!known) additions.add(candidate);
        }
        if (additions.isEmpty()) return text;
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        boolean inProfile = false, found = false;
        int categoryLine = -1, insertion = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (inProfile) insertion = i;
                inProfile = line.substring(1, line.length() - 1).trim().equalsIgnoreCase("App." + profile.id);
                if (inProfile) { found = true; insertion = lines.size(); }
            } else if (inProfile) {
                int equals = line.indexOf('=');
                if (equals >= 0 && line.substring(0, equals).trim().equalsIgnoreCase("Kategorie"))
                    categoryLine = i;
            }
        }
        if (!found) return withCategory(withDefaults(text), profileId, entered);
        if (categoryLine >= 0) {
            String raw = lines.get(categoryLine);
            String value = raw.substring(raw.indexOf('=') + 1).trim();
            lines.set(categoryLine, raw.substring(0, raw.indexOf('=') + 1) + value
                    + (value.isEmpty() ? "" : ", ") + String.join(", ", additions));
        } else {
            List<String> values = new ArrayList<>(profile.categories);
            values.addAll(additions);
            if (insertion == lines.size() && lines.get(lines.size() - 1).isEmpty()) insertion--;
            lines.add(insertion, "Kategorie=" + String.join(", ", values));
        }
        String updated = String.join(newline, lines);
        parse(updated);
        return updated;
    }

    static String withDefaults(String text) throws IOException {
        Map<String, Map<String, String>> sections = sections(text);
        boolean hasProfiles = false;
        for (String section : sections.keySet())
            if (section.regionMatches(true, 0, "App.", 0, 4)) hasProfiles = true;
        String oldCategories = legacyCategoryValue(text);
        StringBuilder result = new StringBuilder();
        String current = "";
        boolean skip = false;
        boolean hasCategory = false;
        String[] lines = TextSnippets.lines(text).toArray(new String[0]);
        for (int position = 0; position < lines.length; position++) {
            String raw = lines[position];
            if (position == lines.length - 1 && raw.isEmpty()) break;
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (hasProfiles && current.regionMatches(true, 0, "App.", 0, 4) && !hasCategory)
                    result.append("Kategorie=").append(oldCategories).append('\n');
                current = line.substring(1, line.length() - 1).trim();
                skip = current.equalsIgnoreCase("Kategorie") || current.equalsIgnoreCase("Pfade")
                        || (!hasProfiles && current.equalsIgnoreCase("App"));
                hasCategory = false;
            } else if (!skip && current.regionMatches(true, 0, "App.", 0, 4)) {
                int equals = line.indexOf('=');
                if (equals >= 0 && line.substring(0, equals).trim().equalsIgnoreCase("Kategorie")) hasCategory = true;
            }
            if (!skip) {
                String written = raw;
                int equals = line.indexOf('=');
                if (equals >= 0 && line.substring(0, equals).trim().equalsIgnoreCase("Daten")) {
                    String configured = line.substring(equals + 1).trim();
                    if ((current.equalsIgnoreCase("App.Chaosbox") || current.equalsIgnoreCase("App.Chaobox"))
                            && configured.equalsIgnoreCase("ChaosBox/daten")) written = "Daten=ChaosBox/boxes";
                    else if (current.equalsIgnoreCase("App.Bilderbox")
                            && configured.equalsIgnoreCase("Bilderbox/daten")) written = "Daten=Bilderbox/boxes";
                }
                result.append(written).append('\n');
            }
        }
        if (hasProfiles) {
            if (current.regionMatches(true, 0, "App.", 0, 4) && !hasCategory)
                result.append("Kategorie=").append(oldCategories).append('\n');
        } else {
            Profile legacy = parse(text).profiles.get(0);
            result.append("\n[App]\nStandard=Chaosbox\n\n[App.Chaosbox]\nTitel=Chaosbox\nBilder=")
                    .append(legacy.images).append("\nDaten=")
                    .append(legacy.data.equalsIgnoreCase("ChaosBox/daten")
                            ? "ChaosBox/boxes" : legacy.data)
                    .append("\nFelder=").append(DEFAULT_FIELDS)
                    .append("\nKategorie=").append(oldCategories).append('\n');
        }
        if (sections.keySet().stream().noneMatch(
                section -> section.equalsIgnoreCase("ImageSize")))
            result.append("\n[ImageSize]\nLIMIT=3000\n");
        if (sections.keySet().stream().noneMatch(
                section -> section.equalsIgnoreCase("TextSnippets")))
            result.append(TextSnippets.DEFAULT_SECTION);
        String converted = result.toString();
        parse(converted);
        return converted.equals(text) ? text : converted;
    }
}
