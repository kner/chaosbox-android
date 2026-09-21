package local.sshcopy;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

public final class UploadService extends Service {
    private static final String CHANNEL = "uploads";
    private static final int NOTIFICATION = 41;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int latestStart;
    private boolean running;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("ChaosBox").setContentText(text).setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        startForeground(NOTIFICATION, notification("Upload läuft im Hintergrund …"));
        if (!running) { running = true; upload(startId); }
        return START_NOT_STICKY;
    }

    private void upload(int startId) {
        executor.execute(() -> {
            String failure = null;
            try {
                DataIndex.rebuild(new File(Environment.getExternalStorageDirectory(), "ChaosBox"), file -> {
                    String raw = EditorActivity.ImageProcessor.readUserComment(new FileInputStream(file));
                    try { return new JSONObject(raw); }
                    catch (JSONException e) { return new JSONObject().put("comment", raw); }
                });
            } catch (Exception e) { failure = "Index: " + e.getMessage(); }
            try { new UploadFiles(this, message -> {}).copy(); }
            catch (Exception e) { failure = (failure == null ? "" : failure + "\n") + "Upload: " + e.getMessage(); }
            final String error = failure;
            new Handler(getMainLooper()).post(() -> {
                if (latestStart != startId) { upload(latestStart); return; }
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (error != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                            || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        getSystemService(NotificationManager.class).notify(NOTIFICATION + 1,
                                new Notification.Builder(this, CHANNEL)
                                        .setSmallIcon(android.R.drawable.stat_notify_error)
                                        .setContentTitle("Lokal gespeichert; Synchronisierung fehlgeschlagen")
                                        .setContentText(error).setStyle(new Notification.BigTextStyle().bigText(error))
                                        .setAutoCancel(true).build());
                    }
                    android.widget.Toast.makeText(this, "Lokal gespeichert; " + error,
                            android.widget.Toast.LENGTH_LONG).show();
                } else getSystemService(NotificationManager.class).cancel(NOTIFICATION + 1);
                stopSelfResult(startId);
            });
        });
    }

    @Override public void onTimeout(int startId, int fgsType) {
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
