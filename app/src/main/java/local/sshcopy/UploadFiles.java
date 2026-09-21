package local.sshcopy;

import android.content.Context;
import android.os.Environment;
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
    private final Context context;
    private final Consumer<String> log;

    UploadFiles(Context context, Consumer<String> log) {
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
            File storage = Environment.getExternalStorageDirectory();
            for (String path : new String[]{Config.SOURCE, Config.BOXES}) {
                File folder = new File(storage, path);
                if (!folder.exists()) { show("Ordner fehlt: " + path); continue; }
                for (File file : SourceFiles.list(folder)) {
                    if (file.getName().startsWith(".save-") && file.getName().endsWith(".tmp")) continue;
                    if (path.equals(Config.BOXES)
                            && !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) continue;
                    files.put(file, path.equals(Config.BOXES) ? Config.JSON_DESTINATION : Config.IMAGE_DESTINATION);
                }
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
                        sftp.cd(destination);
                    } catch (com.jcraft.jsch.SftpException e) {
                        throw new IOException("Zielordner nicht erreichbar: " + destination + " — " + e.getMessage(), e);
                    }
                    currentDestination = destination;
                }
                // Recheck before opening; never recurse or follow a source symlink.
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
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
            show("Done. Copied " + copied + " file(s).\nSubdirectories were skipped.");
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

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
