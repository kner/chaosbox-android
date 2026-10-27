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
        final int imageWidth;
        Profile(String id, Map<String, String> values) throws IOException {
            this.id = id;
            imageWidth = width(values.getOrDefault("imagewidth", "3000"));
            title = values.getOrDefault("titel", id.equalsIgnoreCase("Chaobox") ? "Chaosbox" : id).trim();
            if(id.isEmpty()||(id.equals(".")||id.equals(".."))||id.contains("/")||id.contains("\\")||id.matches(".*[\\p{Cntrl}].*"))
                throw new IOException("Invalid app profile directory: "+id);
            images = id+"/JPG";
            data = id+"/TXT";
            if(title.isEmpty())throw new IOException("Box "+id+": Title must not be empty.");
            String[] labels = values.getOrDefault("felder", DEFAULT_FIELDS).split(",", -1);
            if (labels.length > 7) throw new IOException("App." + id + ": Fields has more than seven positions.");
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

    private static AppProfiles parseLegacy(String text) throws IOException {
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
                values.putIfAbsent("imagewidth", Integer.toString(imageLimit(sections)));
                profiles.add(new Profile(id, values));
            }
        }
        if (profiles.isEmpty()) {
            Map<String, String> legacy = new LinkedHashMap<>();
            legacy.put("kategorie", oldCategories);
            legacy.put("imagewidth", Integer.toString(imageLimit(sections)));
            profiles.add(new Profile("Chaosbox", legacy));
        }
        return new AppProfiles(profiles, defaultId, imageLimit(sections), TextSnippets.parse(text));
    }

    private static int width(String value) throws IOException {
        try {
            int result = Integer.parseInt(value.trim());
            if (result >= 1 && result <= 20000) return result;
        } catch (NumberFormatException ignored) { }
        throw new IOException("ImageWidth must be a pixel count from 1 to 20000.");
    }

    private static String setupKey(String key) {
        String normalized=key.trim().toLowerCase(Locale.ROOT);
        switch(normalized) {
            case "title": return "titel";
            case "fields": return "felder";
            case "categories": return "kategorie";
            default: return normalized;
        }
    }
    private static String englishSetup(String text) throws IOException {
        List<String> lines=new ArrayList<>(TextSnippets.lines(text));
        String section="";
        boolean changed=false;
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("^(\\s*(?:[;#]\\s*)?)(Titel|Felder|Kategorie)(\\s*=)",java.util.regex.Pattern.CASE_INSENSITIVE);
        for(int i=0;i<lines.size();i++) {
            String raw=lines.get(i),line=raw.trim();
            if(line.startsWith("[")&&line.endsWith("]")) section=line.substring(1,line.length()-1).trim();
            else if(section.equalsIgnoreCase("App")||section.equalsIgnoreCase("Box")) {
                java.util.regex.Matcher match=pattern.matcher(raw);
                if(match.find()) {
                    String key=match.group(2).equalsIgnoreCase("Titel")?"Title":match.group(2).equalsIgnoreCase("Felder")?"Fields":"Categories";
                    lines.set(i,match.group(1)+key+match.group(3)+raw.substring(match.end()));changed=true;
                }
            }
        }
        if(!changed)return text;
        String result=String.join("\n",lines);
        return text.contains("\r\n")?result.replace("\r\n","\n").replace("\n","\r\n"):result;
    }

    private static final class Block {
        final String name;
        final Map<String,String> values = new LinkedHashMap<>();
        final List<String> lines = new ArrayList<>();
        Block(String name) { this.name = name; }
    }
    private static List<Block> blocks(String text) throws IOException {
        List<Block> result = new ArrayList<>();
        Block current = null;
        for (String raw : TextSnippets.lines(text)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                current = new Block(line.substring(1, line.length()-1).trim());
                result.add(current);
            } else if (current != null) {
                current.lines.add(raw);
                int equals = line.indexOf('=');
                if (equals >= 0 && !line.startsWith(";") && !line.startsWith("#"))
                    current.values.put(setupKey(line.substring(0,equals)), line.substring(equals+1).trim());
            }
        }
        return result;
    }
    private static boolean hasBoxes(String text) throws IOException {
        for (Block block : blocks(text)) if (block.name.equalsIgnoreCase("Box")) return true;
        return false;
    }
    static AppProfiles parse(String text) throws IOException {
        if (!hasBoxes(text)) return parseLegacy(text);
        Map<String,String> defaults = new LinkedHashMap<>();
        List<Block> source = blocks(text);
        for (Block block : source) {
            if (block.name.equalsIgnoreCase("App")) defaults.putAll(block.values);
            if (block.name.regionMatches(true,0,"App.",0,4) || block.name.equalsIgnoreCase("ImageSize"))
                throw new IOException("Use [Box] and ImageWidth; do not mix legacy setup sections.");
        }
        if (defaults.containsKey("standard")) throw new IOException("Standard is obsolete; the first Box is selected by default.");
        List<Profile> profiles = new ArrayList<>();
        for (Block block : source) if (block.name.equalsIgnoreCase("Box")) {
            Map<String,String> values = new LinkedHashMap<>(defaults);
            values.putAll(block.values);
            String title = block.values.getOrDefault("titel", "").trim();
            if (title.isEmpty() || (title.equals(".")||title.equals("..")) || title.contains("/") || title.contains("\\")
                    || Arrays.asList("jpg","txt",".indices",".state","setup.ini").contains(title.toLowerCase(Locale.ROOT)))
                throw new IOException("Box Title must be a non-reserved folder name.");
            for (String key : Arrays.asList("standard","jpg","bilder","daten"))
                if (values.containsKey(key)) throw new IOException(key+" is obsolete; use fixed JPG/TXT folders.");
            for (Profile old : profiles) if (old.id.equalsIgnoreCase(title))
                throw new IOException("Duplicate Box Title: "+title);
            profiles.add(new Profile(title, values));
        }
        return new AppProfiles(profiles, "", width(defaults.getOrDefault("imagewidth","3000")), TextSnippets.parse(text));
    }

    private static String boxCategory(String text, String id, String entered) throws IOException {
        text=englishSetup(text);
        if (entered.contains("\n") || entered.contains("\r")) throw new IOException("Category must be a single line.");
        Profile profile = parse(text).find(id);
        if (profile == null) throw new IOException("Box not found: "+id);
        List<String> categories = new ArrayList<>(profile.categories);
        for (String candidate : splitCategories(entered))
            if (categories.stream().noneMatch(value -> value.equalsIgnoreCase(candidate))) categories.add(candidate);
        if (categories.equals(profile.categories)) return text;
        List<String> lines = new ArrayList<>(TextSnippets.lines(text));
        int begin = -1, end = lines.size(), category = -1;
        for (int i=0; i<lines.size(); i++) {
            String line=lines.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (begin>=0) {end=i;break;}
                if (line.equalsIgnoreCase("[Box]")) {
                    for (int j=i+1;j<lines.size();j++) {
                        String candidate=lines.get(j).trim();
                        if (candidate.startsWith("[") && candidate.endsWith("]")) break;
                        int equals=candidate.indexOf('=');
                        if (equals>=0 && setupKey(candidate.substring(0,equals)).equals("titel")
                                && candidate.substring(equals+1).trim().equalsIgnoreCase(id)) {begin=i;break;}
                    }
                }
            } else if (begin>=0 && line.matches("(?i)(kategorie|categories)\\s*=.*")) category=i;
        }
        if (begin<0) throw new IOException("Box not found: "+id);
        String value="Categories="+String.join(", ", categories);
        if (category>=0) lines.set(category,value); else lines.add(end,value);
        String newline=text.contains("\r\n")?"\r\n":"\n";
        String result=String.join("\n",lines);
        if (!newline.equals("\n")) result=result.replace("\r\n","\n").replace("\n",newline);
        parse(result);
        return result;
    }

    static String withDefaults(String text) throws IOException {
        text=englishSetup(text);
        if (hasBoxes(text)) {
            parse(text);
            boolean snippets=false;
            for (Block block:blocks(text)) if (block.name.equalsIgnoreCase("TextSnippets")) snippets=true;
            return snippets ? text : text + TextSnippets.DEFAULT_SECTION;
        }
        String normalized=legacyDefaults(text);
        AppProfiles legacy=parseLegacy(normalized);
        StringBuilder result=new StringBuilder("[App]\nImageWidth="+legacy.imageLimit+"\n");
        for (Block block:blocks(normalized)) if (block.name.equalsIgnoreCase("App"))
            for (Map.Entry<String,String> entry:block.values.entrySet())
                if (!entry.getKey().equals("standard") && !entry.getKey().equals("imagewidth"))
                    result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        List<Profile> ordered=new ArrayList<>();
        ordered.add(legacy.find(legacy.defaultId));
        for (Profile profile:legacy.profiles) if (!profile.id.equalsIgnoreCase(legacy.defaultId)) ordered.add(profile);
        for (Profile profile:ordered) {
            // The original ID owns the on-disk folder; never rename it during migration.
            result.append("\n[Box]\nTitle=").append(profile.id)
                .append("\nFields=").append(String.join(",",profile.fields))
                .append("\nCategories=").append(String.join(", ",profile.categories)).append('\n');
        }
        for (Block block:blocks(normalized)) {
            if (block.name.equalsIgnoreCase("App") || block.name.equalsIgnoreCase("ImageSize")
                    || block.name.regionMatches(true,0,"App.",0,4)) continue;
            result.append("\n[").append(block.name).append("]\n");
            for (String line:block.lines) result.append(line).append('\n');
        }
        String converted=result.toString();
        parse(converted);
        return converted;
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
                if (equals >= 0) values.put(setupKey(line.substring(0, equals)), line.substring(equals + 1).trim());
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
        if (hasBoxes(text)) return boxCategory(text, profileId, entered);
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

    private static String legacyDefaults(String text) throws IOException {
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
                int equals=line.indexOf('=');
                if(current.regionMatches(true,0,"App.",0,4)&&equals>=0&&Arrays.asList("jpg","bilder","daten").contains(line.substring(0,equals).trim().toLowerCase(Locale.ROOT)))continue;
                result.append(written).append('\n');
            }
        }
        if (hasProfiles) {
            if (current.regionMatches(true, 0, "App.", 0, 4) && !hasCategory)
                result.append("Kategorie=").append(oldCategories).append('\n');
        } else {
            result.append("\n[App]\nStandard=Chaosbox\n\n[App.Chaosbox]\nTitel=Chaosbox")
                    .append("\nFields=").append(DEFAULT_FIELDS)
                    .append("\nCategories=").append(oldCategories).append('\n');
        }
        if (sections.keySet().stream().noneMatch(
                section -> section.equalsIgnoreCase("ImageSize")))
            result.append("\n[ImageSize]\nLIMIT=3000\n");
        if (sections.keySet().stream().noneMatch(
                section -> section.equalsIgnoreCase("TextSnippets")))
            result.append(TextSnippets.DEFAULT_SECTION);
        String converted = result.toString();
        parseLegacy(converted);
        return converted.equals(text) ? text : converted;
    }
}
