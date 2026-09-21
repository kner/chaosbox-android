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
import java.io.*;


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

    private void copy() {
        try {
            new UploadFiles(this, this::show).copy();
        } catch (IOException e) {
            show(e.getMessage());
        } finally {
            runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
    }
}
