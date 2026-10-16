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
    private String generation;
    private volatile Session activeSession;
    private volatile ChannelSftp activeChannel;

    void cancel() {
        cancelled = true;
        Session connection = activeSession;
        // Once SFTP is open, progress callbacks abort transfers while finally releases the journal lock.
        if (connection != null && activeChannel == null) connection.disconnect();
    }

    private void checkCancelled() throws IOException {
        if(generation!=null&&!generation.equals(InstallationFiles.existingGeneration(Config.root(context))))
            throw new IOException("Installation directory was reset. Reopen the app and configure synchronization again.");
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

        File directory=InstallationFiles.owned(Config.root(context),".state/credentials");
        Files.createDirectories(directory.toPath());
        File file = new File(directory, name);
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
        Session session=null; ChannelSftp sftp=null;
        try {
            generation=InstallationFiles.existingGeneration(Config.root(context));
            checkCancelled();
            SyncStore store=SyncSftp.store(paths);
            try (AutoCloseable ignored=store.lock()) {
                java.util.Map<String,String> config=SyncSftp.settings(context);
                Security.removeProvider("BC");Security.addProvider(new BouncyCastleProvider());
                JSch ssh=new JSch();ssh.setKnownHosts(credential(Config.KNOWN_HOSTS_FILE).getAbsolutePath());
                ssh.addIdentity(credential(Config.KEY_FILE).getAbsolutePath());
                session=ssh.getSession(config.get("user"),config.get("host"),Integer.parseInt(config.get("port")));
                activeSession=session;session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","publickey");session.setTimeout(30000);
                show("Synchronizing over SFTP …");session.connect(15000);checkCancelled();
                sftp=(ChannelSftp)session.openChannel("sftp");sftp.connect(15000);activeChannel=sftp;
                String destination=config.getOrDefault("sftpdatadestination",config.get("datadestination"));
                if(destination.contains("..")||destination.contains("\n")||destination.trim().isEmpty())throw new IOException("Invalid sync destination");
                SyncSftp.exchange(store,sftp,destination+"/.chaosbox-sync",this::checkCancelled,this::show);
                checkCancelled();
                java.util.Map<String,java.util.List<org.json.JSONObject>> conflicts=store.apply();
                show("Synchronization completed. "+conflicts.size()+" conflict(s); all versions retained.");
                if(!conflicts.isEmpty())show("Long press synchronization to inspect and resolve conflicts.");
            }
        } catch(Exception e) {throw new IOException("Synchronization failed: "+UploadErrors.describe(e),e);}
        finally {activeSession=null;activeChannel=null;if(sftp!=null)sftp.disconnect();if(session!=null)session.disconnect();}
    }

}
