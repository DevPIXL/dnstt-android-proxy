package com.devpixl.dnstt;

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
    // We'll keep a small buffer of logs so they appear when you reopen the app
    public static StringBuilder logBuffer = new StringBuilder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if ("STOP".equals(action)) {
            stopTunnel();
            return START_NOT_STICKY;
        }

        // 1. Start Foreground Notification immediately
        startForeground(1, createNotification("Tunnel Starting..."));

        String domain = intent.getStringExtra("domain");
        String key = intent.getStringExtra("key");
        
        startTunnel(domain, key);

        return START_STICKY;
    }

    private void startTunnel(String domain, String pubKeyContent) {
        if (isRunning) return;
        isRunning = true;
        
        // Broadcast "STARTED" status immediately so button updates
        broadcastStatus(true);
        logToUI("Service: Starting tunnel for " + domain + "...");

        new Thread(() -> {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(1, createNotification("Tunnel Connected: " + domain));

                File keyFile = new File(getCacheDir(), "pub.key");
                FileOutputStream fos = new FileOutputStream(keyFile);
                fos.write(pubKeyContent.getBytes());
                fos.close();

                String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
                String[] cmd = {
                    binaryPath,
                    "-udp", "8.8.8.8:53",
                    "-pubkey-file", keyFile.getAbsolutePath(),
                    domain,
                    "127.0.0.1:1080"
                };

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                proxyProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logToUI(line);
                }

            } catch (Exception e) {
                logToUI("Error: " + e.getMessage());
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
        
        broadcastStatus(false);
        logToUI("Service: Tunnel Stopped.");
    }

    private void broadcastStatus(boolean running) {
        Intent i = new Intent("com.devpixl.dnstt.STATUS_UPDATE");
        i.setPackage(getPackageName()); // Explicit broadcast for reliability
        i.putExtra("running", running);
        sendBroadcast(i);
    }

    private void logToUI(String message) {
        // Append to static buffer
        if (logBuffer.length() > 5000) logBuffer.setLength(0); // Prevent overflow
        logBuffer.append(message).append("\n");

        // Broadcast to Activity
        Intent i = new Intent("com.devpixl.dnstt.LOG_UPDATE");
        i.setPackage(getPackageName()); // Explicit broadcast for reliability
        i.putExtra("log", message);
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
                .setSmallIcon(R.drawable.ic_app_icon) // Use our new icon
                .setContentIntent(pi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
