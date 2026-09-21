package local.sshcopy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.TextView;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;


public final class MainActivity extends Activity {

    private TextView status;
    private TextView recordsView;
    private boolean started;
    private boolean permissionRequested;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(32, 64, 32, 32);
        status.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        status.setVerticalScrollBarEnabled(true);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        recordsView = new TextView(this);
        recordsView.setTextSize(16);
        recordsView.setPadding(24, 24, 24, 24);
        recordsView.setTextIsSelectable(true);
        recordsView.setText("ChaosBox wird geladen …");
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(recordsView);
        layout.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 2));
        layout.addView(status, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("SSH Copy kner 2026");
    }

    @Override
    public void onResume() {
        super.onResume();
        if (started) {
            return;
        }
        boolean allowed = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!allowed) {
            status.setText("Allow storage access to copy files automatically. Reopen the app if access was denied.");
            if (!permissionRequested) {
                permissionRequested = true;
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } else {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
                }
            }
            return;
        }
        started = true;
        new Thread(() -> {
            loadLocalData();
            copy();
        }, "ssh-copy").start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            onResume();
        }
    }

    private void show(String message) {
        runOnUiThread(() -> {
            status.append(message + "\n");

            status.post(() -> {
                if (status.getLayout() == null) {
                    return;
                }

                int visibleHeight = status.getHeight()
                        - status.getTotalPaddingTop()
                        - status.getTotalPaddingBottom();

                int scrollY = status.getLayout().getHeight() - visibleHeight;
                status.scrollTo(0, Math.max(0, scrollY));
            });
        });
    }

    private void loadLocalData() {
        File storage = Environment.getExternalStorageDirectory();
        // Keep the record display separate from the scrolling transfer log.
        StringBuilder text = new StringBuilder();
        try {
            String setup = LocalData.read(new File(storage, Config.SETUP));
            text.append("Setup geladen: ").append(Config.SETUP)
                    .append(" ( ").append(setup.length()).append(" Zeichen)\n\n");
        } catch (Exception e) {
            text.append("Setup: ").append(e.getMessage()).append("\n\n");
        }
        int count = 0;
        try {
            for (File file : SourceFiles.list(new File(storage, Config.BOXES))) {
                if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) continue;
                try {
                    java.util.List<String> records = LocalData.records(LocalData.read(file));
                    for (int i = 0; i < records.size(); i++) {
                        text.append(file.getName()).append(" — Datensatz ").append(i + 1)
                                .append("\n").append(records.get(i)).append("\n\n");
                        count++;
                    }
                } catch (Exception e) {
                    text.append(file.getName()).append(": ").append(e.getMessage()).append("\n\n");
                }
            }
        } catch (Exception e) {
            text.append("Boxes: ").append(e.getMessage()).append("\n");
        }
        final String content = count + " Datensätze\n\n" + text;
        runOnUiThread(() -> recordsView.setText(content));
    }

    private File credential(String name) throws IOException {

        File file = new File(getFilesDir(), name);
        if (!file.isFile()) {
            try (InputStream in = getAssets().open(name); OutputStream out = new FileOutputStream(file)) {
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

    private void copy() {
        Session session = null;
        ChannelSftp sftp = null;
        int copied = 0;
        try {
            show("los gehts\n");
            java.util.List<File> files = new java.util.ArrayList<>();
            File storage = Environment.getExternalStorageDirectory();
            for (String path : new String[]{Config.SOURCE, Config.BOXES}) {
                File folder = new File(storage, path);
                if (!folder.exists()) { show("Ordner fehlt: " + path); continue; }
                for (File file : SourceFiles.list(folder)) {
                    if (path.equals(Config.BOXES)
                            && !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) continue;
                    files.add(file);
                }
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            for (File file : files) {
                if (!names.add(file.getName())) throw new IOException("Doppelter Dateiname in JPG und boxes: " + file.getName());
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
            sftp.cd(Config.DESTINATION);
            for (File file : files) {
                // Recheck before opening; never recurse or follow a source symlink.
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Source changed: " + file.getName());
                }

                String remote = escape(file.getName());
                boolean skip = RemoteFiles.shouldSkip(sftp, remote, file.lastModified());

                if (!skip) {

                    show("Copying " + (copied + 1) + "/" + files.size() + "\n" + file.getName());
                    try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        sftp.put(in, remote, ChannelSftp.OVERWRITE);
                    }
                    // Preserve source time so the next run can reliably compare versions.
                    sftp.setMtime(remote, (int) (file.lastModified() / 1000));
                    copied++;
                }
            }
            show("Done. Copied " + copied + " file(s).\nSubdirectories were skipped.");
        } catch (Exception e) {
            show("Transfer stopped after " + copied + " file(s).\n" + e.getMessage());
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
            runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
    }

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
