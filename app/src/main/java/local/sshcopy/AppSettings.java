package local.sshcopy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Selected profile is private app state, independent of the shared setup file. */
final class AppSettings {
    static final class Selection {
        final AppProfiles config;
        final AppProfiles.Profile profile;
        final StoragePaths paths;
        Selection(AppProfiles config, AppProfiles.Profile profile) throws IOException {
            this.config = config;
            this.profile = profile;
            paths = StoragePaths.forProfile(Environment.getExternalStorageDirectory(), profile);
        }
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app-profile", Context.MODE_PRIVATE);
    }
    static AppProfiles configuration(Context context) throws IOException {
        File file = new File(Environment.getExternalStorageDirectory(), Config.SETUP);
        String text;
        if (file.isFile() && file.canRead()) {
            text = LocalData.read(file);
        } else {
            text = preferences(context).getString("setup-cache", null);
            if (text == null) {
                try (InputStream in = context.getAssets().open(Config.SETUP_ASSET);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        AppProfiles config = AppProfiles.parse(text);
        validate(config);
        preferences(context).edit().putString("setup-cache", text).apply();
        return config;
    }
    static void validate(AppProfiles config) throws IOException {
        java.util.List<File> roots = new java.util.ArrayList<>();
        for (AppProfiles.Profile profile : config.profiles) {
            StoragePaths paths = StoragePaths.forProfile(Environment.getExternalStorageDirectory(), profile);
            java.util.Set<File> profileRoots = new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList(paths.images, paths.data, paths.legacyImages, paths.legacyData));
            for (File root : profileRoots) {
                for (File previous : roots) if (root.toPath().startsWith(previous.toPath()) || previous.toPath().startsWith(root.toPath()))
                    throw new IOException("App profiles require separate image and data folders: " + root);
                roots.add(root);
            }
        }
    }
    static Selection rememberCategory(Context context, Selection current, String category) throws IOException {
        if (category.trim().isEmpty()) return current;
        File file = new File(Environment.getExternalStorageDirectory(), Config.SETUP);
        String original = LocalData.read(file);
        String updated = AppProfiles.withCategory(original, current.profile.id, category);
        AppProfiles config = AppProfiles.parse(updated);
        validate(config);
        Selection result = new Selection(config, config.find(current.profile.id));
        if (!updated.equals(original)) {
            android.util.AtomicFile target = new android.util.AtomicFile(file);
            FileOutputStream out = null;
            try {
                out = target.startWrite();
                out.write(updated.getBytes(StandardCharsets.UTF_8));
                target.finishWrite(out);
            } catch (IOException e) {
                if (out != null) target.failWrite(out);
                throw e;
            }
        }
        preferences(context).edit().putString("setup-cache", updated).apply();
        return result;
    }

    static Selection load(Context context) throws IOException {
        AppProfiles config = configuration(context);
        return new Selection(config, config.selected(preferences(context).getString("selected", "")));
    }
    static Selection select(Context context, String id) throws IOException {
        AppProfiles config = configuration(context);
        AppProfiles.Profile profile = config.find(id);
        if (profile == null) throw new IOException("App profile not found: " + id);
        Selection selection = new Selection(config, profile);
        if (!preferences(context).edit().putString("selected", id).commit())
            throw new IOException("Could not save profile selection.");
        return selection;
    }
}
