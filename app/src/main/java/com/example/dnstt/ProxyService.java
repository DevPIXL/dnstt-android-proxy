package com.example.dnstt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class ProxyService extends Service {

    private Process proxyProcess;
    public static boolean isRunning = false;
    public static String lastLog = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if ("STOP".equals(action)) {
            stopTunnel();
            return START_NOT_STICKY;
        }

        // 1. Start Foreground Notification (Required to keep net access)
        startForeground(1, createNotification("Tunnel Starting..."));

        // 2. Get args
        String domain = intent.getStringExtra("domain");
        String key = intent.getStringExtra("key");
        
        // 3. Start the Binary
        startTunnel(domain, key);

        return START_STICKY;
    }

    private void startTunnel(String domain, String pubKeyContent) {
        if (isRunning) return;
        isRunning = true;

        new Thread(() -> {
            try {
                // Update Notification
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(1, createNotification("Tunnel Running: " + domain));

                // Save Key File
                File keyFile = new File(getCacheDir(), "pub.key");
                FileOutputStream fos = new FileOutputStream(keyFile);
                fos.write(pubKeyContent.getBytes());
                fos.close();

                // Build Command
                String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
                String[] cmd = {
                    binaryPath,
                    "-udp", "8.8.8.8:53",
                    "-pubkey-file", keyFile.getAbsolutePath(),
                    domain,
                    "127.0.0.1:1080"
                };

                // Run Process
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                proxyProcess = pb.start();

                // Read Logs (and broadcast them to UI if open)
                BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    lastLog = line;
                    // Send log to MainActivity (if alive)
                    Intent i = new Intent("com.example.dnstt.LOG_UPDATE");
                    i.putExtra("log", line);
                    sendBroadcast(i);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                stopTunnel();
            }
        }).start();
    }

    private void stopTunnel() {
        isRunning = false;
        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }
        stopForeground(true);
        stopSelf();
        
        Intent i = new Intent("com.example.dnstt.STATUS_UPDATE");
        i.putExtra("running", false);
        sendBroadcast(i);
    }

    private Notification createNotification(String text) {
        String channelId = "dnstt_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Tunnel Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("DNSTT Proxy")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
