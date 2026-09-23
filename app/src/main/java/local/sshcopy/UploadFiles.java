package local.sshcopy;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.Security;
import java.util.function.Consumer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class UploadFiles {
    private final StoragePaths paths;
    private final Context context;
    private final Consumer<String> log;

    UploadFiles(Context context, Consumer<String> log, StoragePaths paths) {
        this.paths = paths;
        this.context = context.getApplicationContext();
        this.log = log;
    }

    private void show(String message) { log.accept(message); }

    private File credential(String name) throws IOException {

        File file = new File(context.getFilesDir(), name);
        if (!file.isFile()) {
            try (InputStream in = context.getAssets().open(name); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                file.delete();
                throw new IOException("Missing " + name + ". Install credentials locally as described in README.md.");
            }
        }
        return file;
    }

    synchronized void copy() throws IOException {
        Session session = null;
        ChannelSftp sftp = null;
        int copied = 0;
        try {
            show("los gehts\n");
            java.util.Map<File, String> files = new java.util.LinkedHashMap<>();
            for (File file : paths.imageFiles()) {
                if (!paths.canUpload(file)) continue;
                String subfolder = remoteSubfolder(file, paths.images, paths.legacyImages);
                files.put(file, Config.IMAGE_DESTINATION + subfolder);
            }
            for (File file : paths.jsonFiles()) {
                if (!paths.canUpload(file)) continue;
                String subfolder = remoteSubfolder(file, paths.data, paths.legacyData);
                files.put(file, Config.JSON_DESTINATION + subfolder);
            }
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (java.util.Map.Entry<File, String> entry : files.entrySet()) {
                if (!targets.add(entry.getValue() + "/" + entry.getKey().getName()))
                    throw new IOException("Mehrere lokale Dateien haben dasselbe Upload-Ziel: " + entry.getKey().getName());
            }
            if (files.isEmpty()) { show("Keine Dateien zum Hochladen."); return; }
            Security.removeProvider("BC");
            Security.addProvider(new BouncyCastleProvider());
            JSch ssh = new JSch();
            ssh.setKnownHosts(credential(Config.KNOWN_HOSTS_FILE).getAbsolutePath());
            ssh.addIdentity(credential(Config.KEY_FILE).getAbsolutePath());
            session = ssh.getSession(Config.USER, Config.HOST, Config.PORT);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setTimeout(30000);
            session.connect(15000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15000);
            String remoteHome = sftp.pwd();
            String currentDestination = null;
            for (java.util.Map.Entry<File, String> entry : files.entrySet()) {
                File file = entry.getKey();
                String destination = entry.getValue();
                if (!destination.equals(currentDestination)) {
                    sftp.cd(remoteHome);
                    try {
                        String base = destination.startsWith(Config.IMAGE_DESTINATION)
                                ? Config.IMAGE_DESTINATION : Config.JSON_DESTINATION;
                        sftp.cd(base);
                        if (!destination.equals(base)) {
                            String folder = destination.substring(base.length() + 1);
                            for (String part : folder.split("/")) {
                                try { sftp.cd(escape(part)); }
                                catch (com.jcraft.jsch.SftpException missing) {
                                    if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
                                    sftp.mkdir(part);
                                    sftp.cd(escape(part));
                                }
                            }
                        }
                    } catch (com.jcraft.jsch.SftpException e) {
                        throw new IOException("Zielordner nicht erreichbar: " + destination + " — " + e.getMessage(), e);
                    }
                    currentDestination = destination;
                }
                // Recheck before opening; never recurse or follow a source symlink.
                if (!paths.canUpload(file) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Source changed: " + file.getName());
                }

                String remote = escape(file.getName());
                long sourceModified = file.lastModified();
                boolean skip = RemoteFiles.shouldSkip(sftp, remote, sourceModified);

                if (!skip) {

                    show("Copying " + (copied + 1) + "/" + files.size() + "\n" + file.getName() + " → " + destination);
                    try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        sftp.put(in, remote, ChannelSftp.OVERWRITE);
                    }
                    // Preserve source time so the next run can reliably compare versions.
                    sftp.setMtime(remote, (int) (sourceModified / 1000));
                    copied++;
                }
            }
            show("Done. Copied " + copied + " file(s).\nKategorieordner wurden berücksichtigt.");
        } catch (Exception e) {
            throw new IOException("Upload nach " + copied + " Datei(en) abgebrochen: " + e.getMessage(), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private static String remoteSubfolder(File file, File primary, File legacy) throws IOException {
        java.nio.file.Path parent = file.getCanonicalFile().getParentFile().toPath();
        java.nio.file.Path root = parent.startsWith(primary.toPath())
                ? primary.toPath() : legacy.toPath();
        if (!parent.startsWith(root)) throw new IOException("Datei außerhalb des Datenordners: " + file);
        String relative = root.relativize(parent).toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "" : "/" + relative;
    }

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
