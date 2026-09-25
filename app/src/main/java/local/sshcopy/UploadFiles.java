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
    private volatile boolean cancelled;
    private volatile Session activeSession;

    void cancel() {
        cancelled = true;
        Session connection = activeSession;
        if (connection != null) connection.disconnect();
    }

    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new IOException("Synchronization cancelled. Please restart manually.");
    }

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
                throw new IOException("SSH credentials are missing or unreadable: " + name + ". Check the setup instructions in README.", e);
            }
        }
        return file;
    }

    synchronized void copy() throws IOException {
        Session session = null;
        ChannelSftp sftp = null;
        int copied = 0;
        int skipped = 0;
        String stage = "Prepare local files";
        try {
            checkCancelled();
            show("Starting synchronization.");
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
                    throw new IOException("Multiple local files have the same upload destination: " + entry.getKey().getName());
            }
            if (files.isEmpty()) { show("No files to upload."); return; }
            checkCancelled();
            stage = "Load SSH credentials";
            Security.removeProvider("BC");
            Security.addProvider(new BouncyCastleProvider());
            JSch ssh = new JSch();
            ssh.setKnownHosts(credential(Config.KNOWN_HOSTS_FILE).getAbsolutePath());
            ssh.addIdentity(credential(Config.KEY_FILE).getAbsolutePath());
            session = ssh.getSession(Config.USER, Config.HOST, Config.PORT);
            activeSession = session;
            checkCancelled();
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setTimeout(30000);
            stage = "Connect via SSH to " + Config.HOST + ":" + Config.PORT + " ";
            show(stage);
            session.connect(15000);
            checkCancelled();
            stage = "Open SFTP connection";
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15000);
            String remoteHome = sftp.pwd();
            String currentDestination = null;
            for (java.util.Map.Entry<File, String> entry : files.entrySet()) {
                checkCancelled();
                File file = entry.getKey();
                String destination = entry.getValue();
                stage = "File " + file.getName() + " → " + destination;
                if (!destination.equals(currentDestination)) {
                    try {
                        RemoteFiles.ensureDirectory(sftp, remoteHome, destination, this::show);
                    } catch (com.jcraft.jsch.SftpException e) {
                        throw new IOException("Could not create or open destination folder: " + destination + " — " + e.getMessage(), e);
                    }
                    currentDestination = destination;
                }
                // Recheck before opening; never recurse or follow a source symlink.
                if (!paths.canUpload(file) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Source file is no longer available or has changed: " + file.getName());
                }

                String remote = escape(file.getName());
                long sourceModified = file.lastModified();
                boolean skip = RemoteFiles.shouldSkip(sftp, remote, sourceModified);

                if (!skip) {

                    show("Uploading: " + file.getName() + " → " + destination);
                    // Publish only complete files. A cancelled transfer must not replace the server copy.
                    String temporary = ".chaosbox-" + java.util.UUID.randomUUID() + ".upload";
                    boolean published = false;
                    try {
                        try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            sftp.put(in, temporary, new com.jcraft.jsch.SftpProgressMonitor() {
                                public void init(int operation, String source, String target, long max) { }
                                public boolean count(long count) { return !cancelled; }
                                public void end() { }
                            }, ChannelSftp.OVERWRITE);
                        }
                        checkCancelled();
                        sftp.setMtime(temporary, (int) (sourceModified / 1000));
                        sftp.rename(temporary, remote);
                        published = true;
                        copied++;
                    } finally {
                        if (!published && sftp.isConnected()) {
                            try { sftp.rm(temporary); }
                            catch (com.jcraft.jsch.SftpException cleanup) {
                                show("Could not remove temporary server file: " + temporary);
                            }
                        }
                    }
                } else skipped++;
            }
            checkCancelled();
            show("Completed: " + copied + " file(s) uploaded, " + skipped
                    + " unchanged or newer server file(s) skipped.");
        } catch (Exception e) {
            throw new IOException((cancelled ? "Synchronization cancelled" : "Upload failed")
                    + " after " + copied + " file(s).\nStep: " + stage
                    + "\n" + UploadErrors.describe(e), e);
        } finally {
            activeSession = null;
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
        if (!parent.startsWith(root)) throw new IOException("File outside the data folder: " + file);
        String relative = root.relativize(parent).toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "" : "/" + relative;
    }

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
