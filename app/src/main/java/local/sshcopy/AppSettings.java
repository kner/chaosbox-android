package local.sshcopy;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Profile selection, setup and data belong to this installation directory. */
final class AppSettings {
    static final class Selection {
        final AppProfiles config;
        final AppProfiles.Profile profile;
        final StoragePaths paths;
        Selection(AppProfiles config, AppProfiles.Profile profile, File root) throws IOException {
            this.config = config;
            this.profile = profile;
            paths = StoragePaths.forProfile(root, profile);
        }
    }
    static String defaults(Context context) throws IOException {
        try(InputStream in=context.getAssets().open(Config.SETUP_ASSET);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static AppProfiles configuration(Context context) throws IOException {
        String text=InstallationFiles.setup(Config.root(context),defaults(context));
        AppProfiles config = AppProfiles.parse(text);
        validate(context,config);
        return config;
    }
    static void validate(Context context, AppProfiles config) throws IOException {
        File installationRoot=Config.root(context);
        java.util.List<File> roots = new java.util.ArrayList<>();
        for (AppProfiles.Profile profile : config.profiles) {
            StoragePaths paths = StoragePaths.forProfile(installationRoot, profile);
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
        File file = Config.setup(context);
        String original = LocalData.read(file);
        String updated = AppProfiles.withCategory(original, current.profile.id, category);
        AppProfiles config = AppProfiles.parse(updated);
        validate(context,config);
        Selection result = new Selection(config, config.find(current.profile.id),Config.root(context));
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
        return result;
    }

    static Selection load(Context context) throws IOException {
        AppProfiles config = configuration(context);
        File selected=InstallationFiles.owned(Config.root(context),".state/selected-profile");
        return new Selection(config,config.selected(selected.isFile()?LocalData.read(selected).trim():""),Config.root(context));
    }
    static Selection select(Context context, String id) throws IOException {
        AppProfiles config = configuration(context);
        AppProfiles.Profile profile = config.find(id);
        if (profile == null) throw new IOException("App profile not found: " + id);
        Selection selection = new Selection(config, profile,Config.root(context));
        File selected=InstallationFiles.owned(Config.root(context),".state/selected-profile");
        SyncStore.atomic(selected,id.getBytes(StandardCharsets.UTF_8));
        return selection;
    }
}
