package com.devpixl.dnstt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.devpixl.dnstt.net.PacketHeaders;
import com.devpixl.dnstt.net.TcpConnection;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DnsttVpnService extends VpnService {

    private static final String TAG = "DnsttVpnService";
    private static final String VPN_CHANNEL_ID = "dnstt_vpn_channel";
    private static final int NOTIFICATION_ID = 1;

    public static boolean isServiceRunning = false;
    // [FIX] Added static log buffer so MainActivity can read history
    public static StringBuilder logBuffer = new StringBuilder();

    // Config
    private String proxyHost = "127.0.0.1";
    private int proxyPort = 1080;
    private String transportDns = "8.8.8.8";
    private String internalDns = "8.8.8.8";

    // State
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private ParcelFileDescriptor vpnInterface;
    private Process proxyProcess;

    private PowerManager.WakeLock wakeLock;
    private ExecutorService dnsThreadPool;
    private Timer connectionCleaner;

    // TCP State Management
    private ConcurrentHashMap<String, TcpConnection> tcpConnections = new ConcurrentHashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if ("STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        String domain = intent.getStringExtra("domain");
        String pubKey = intent.getStringExtra("key");
        String dns = intent.getStringExtra("dns");

        if (dns != null && !dns.isEmpty()) {
            this.transportDns = dns;
        }

        startVpn(domain, pubKey);
        return START_STICKY;
    }

    private void startVpn(String domain, String pubKey) {
        if (isRunning.get()) return;
        isRunning.set(true);
        isServiceRunning = true;

        // [FIX] Clear old logs on new run
        logBuffer.setLength(0);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DnsttVpn:KeepAlive");
            wakeLock.acquire();
        }

        dnsThreadPool = Executors.newFixedThreadPool(50);

        connectionCleaner = new Timer();
        connectionCleaner.schedule(new TimerTask() {
            @Override
            public void run() {
                cleanupStaleConnections();
            }
        }, 60000, 60000);

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification("Starting Tunnel..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Starting Tunnel..."));
        }

        sendLog("Starting VPN Service...");

        new Thread(() -> {
            try {
                startDnsttBinary(domain, pubKey, transportDns);

                if (!establishVpnInterface()) {
                    sendLog("Error: Failed to establish VPN interface");
                    stopVpn();
                    return;
                }

                sendLog("VPN Interface Established.");
                updateNotification("Connected to " + domain);
                sendStatus(true);

                runVpnLoop();

            } catch (Exception e) {
                sendLog("Critical Error: " + e.getMessage());
                Log.e(TAG, "VPN startup failed", e);
                stopVpn();
            }
        }).start();
    }

    private void cleanupStaleConnections() {
        long now = System.currentTimeMillis();
        long timeout = 60000;
        tcpConnections.entrySet().removeIf(entry -> {
            boolean idle = (now - entry.getValue().lastActivity) > timeout;
            if (idle) {
                entry.getValue().close();
            }
            return idle;
        });
    }

    private boolean establishVpnInterface() {
        try {
            Builder builder = new Builder();
            builder.setSession("DNSTT Tunnel");
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);

            try {
                builder.addAddress("fd00::1", 128);
                builder.addRoute("::", 0);
            } catch (IllegalArgumentException ignored) {}

            builder.addDnsServer(internalDns);
            builder.setMtu(1280);
            builder.setBlocking(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addDisallowedApplication(getPackageName());
            }

            vpnInterface = builder.establish();
            return vpnInterface != null;
        } catch (Exception e) {
            sendLog("VPN Builder Error: " + e.getMessage());
            return false;
        }
    }

    private void startDnsttBinary(String domain, String pubKey, String dnsAddr) throws Exception {
        File keyFile = new File(getCacheDir(), "pub.key");
        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            fos.write(pubKey.getBytes());
        }

        String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
        File binary = new File(binaryPath);

        if (!binary.exists()) {
            throw new IOException("Binary not found at " + binaryPath);
        }

        // System usually handles permissions for nativeLibraryDir, but we set it just in case
        binary.setExecutable(true);

        String[] cmd = {
            binaryPath,
            "-udp", dnsAddr,
            "-pubkey-file", keyFile.getAbsolutePath(),
            domain,
            proxyHost + ":" + proxyPort
        };

        sendLog("Executing binary with DNS: " + dnsAddr);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        proxyProcess = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("pubkey")) continue;
                    sendLog("DNSTT: " + line);
                }
            } catch (Exception ignored) {}
        }).start();

        Thread.sleep(1000);
    }

    private void runVpnLoop() {
        try (FileInputStream vpnInput = new FileInputStream(vpnInterface.getFileDescriptor());
             FileOutputStream vpnOutput = new FileOutputStream(vpnInterface.getFileDescriptor())) {

            ByteBuffer packet = ByteBuffer.allocate(32767);

            while (isRunning.get()) {
                int length = vpnInput.read(packet.array());
                if (length > 0) {
                    byte[] data = new byte[length];
                    System.arraycopy(packet.array(), 0, data, 0, length);

                    PacketHeaders.IPv4Header ipHeader = PacketHeaders.IPv4Header.parse(data);

                    if (ipHeader != null) {
                        if (ipHeader.protocol == 6) {
                            processTcpPacket(ipHeader, vpnOutput);
                        } else if (ipHeader.protocol == 17) {
                            processUdpPacket(ipHeader, vpnOutput);
                        }
                    }
                    packet.clear();
                }
            }
        } catch (Exception e) {
            sendLog("VPN Loop Terminated: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void processTcpPacket(PacketHeaders.IPv4Header ipHeader, FileOutputStream vpnOutput) {
        PacketHeaders.TCPHeader tcpHeader = PacketHeaders.TCPHeader.parse(ipHeader.payload);
        if (tcpHeader == null) return;

        String connectionId = ipHeader.sourceIp.getHostAddress() + ":" + tcpHeader.sourcePort +
                              "->" + ipHeader.destinationIp.getHostAddress() + ":" + tcpHeader.destinationPort;

        if (tcpHeader.isSYN() && !tcpHeader.isACK()) {
            com.devpixl.dnstt.net.Socks5Client socksClient = new com.devpixl.dnstt.net.Socks5Client(
                proxyHost, proxyPort,
                ipHeader.destinationIp.getHostAddress(), tcpHeader.destinationPort
            );

            TcpConnection connection = new TcpConnection(
                ipHeader.sourceIp, tcpHeader.sourcePort,
                ipHeader.destinationIp, tcpHeader.destinationPort,
                vpnOutput, socksClient
            );

            tcpConnections.put(connectionId, connection);

            dnsThreadPool.execute(() -> {
                if (!connection.handleSyn(tcpHeader.sequenceNumber)) {
                    connection.close();
                    tcpConnections.remove(connectionId);
                }
            });

        } else if (tcpConnections.containsKey(connectionId)) {
            TcpConnection connection = tcpConnections.get(connectionId);
            if (tcpHeader.isRST()) {
                connection.close();
                tcpConnections.remove(connectionId);
            } else if (tcpHeader.isFIN()) {
                connection.handleFin(tcpHeader.sequenceNumber);
                tcpConnections.remove(connectionId);
            } else if (tcpHeader.isACK()) {
                connection.handleAck(tcpHeader.sequenceNumber, tcpHeader.acknowledgmentNumber, tcpHeader.payload);
            }
        }
    }

    private void processUdpPacket(PacketHeaders.IPv4Header ipHeader, FileOutputStream vpnOutput) {
        PacketHeaders.UDPHeader udpHeader = PacketHeaders.UDPHeader.parse(ipHeader.payload);
        if (udpHeader == null) return;

        if (udpHeader.destinationPort == 53) {
            dnsThreadPool.execute(() -> {
                try {
                    Socket socksSocket = new Socket(proxyHost, proxyPort);
                    socksSocket.setSoTimeout(5000);
                    DataOutputStream out = new DataOutputStream(socksSocket.getOutputStream());
                    DataInputStream in = new DataInputStream(socksSocket.getInputStream());

                    out.write(new byte[]{0x05, 0x01, 0x00});
                    byte[] authResp = new byte[2];
                    in.readFully(authResp);
                    if (authResp[0] != 0x05 || authResp[1] != 0x00) {
                        socksSocket.close();
                        return;
                    }

                    byte[] ipBytes = InetAddress.getByName(internalDns).getAddress();
                    out.write(0x05);
                    out.write(0x01);
                    out.write(0x00);
                    out.write(0x01);
                    out.write(ipBytes);
                    out.writeShort(53);

                    byte[] connResp = new byte[10];
                    in.readFully(connResp);
                    if (connResp[1] != 0x00) {
                        socksSocket.close();
                        return;
                    }

                    out.writeShort(udpHeader.payload.length);
                    out.write(udpHeader.payload);

                    int respLen = in.readUnsignedShort();
                    byte[] dnsResponse = new byte[respLen];
                    in.readFully(dnsResponse);

                    PacketHeaders.UDPHeader respUdp = new PacketHeaders.UDPHeader();
                    respUdp.sourcePort = 53;
                    respUdp.destinationPort = udpHeader.sourcePort;
                    respUdp.payload = dnsResponse;

                    PacketHeaders.IPv4Header respIp = new PacketHeaders.IPv4Header();
                    respIp.version = 4;
                    respIp.ihl = 5;
                    respIp.ttl = 64;
                    respIp.protocol = 17;
                    respIp.sourceIp = ipHeader.destinationIp;
                    respIp.destinationIp = ipHeader.sourceIp;
                    respIp.identification = ipHeader.identification + 1;

                    byte[] rawResponse = PacketHeaders.UDPHeader.buildUdpPacket(respUdp, respIp);

                    synchronized (vpnOutput) {
                        vpnOutput.write(rawResponse);
                        vpnOutput.flush();
                    }
                    socksSocket.close();

                } catch (Exception e) {
                    Log.e(TAG, "DNS over SOCKS failed: " + e.getMessage());
                }
            });
        }
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN permission revoked by OS");
        stopVpn();
        super.onRevoke();
    }

    private void stopVpn() {
        isRunning.set(false);
        isServiceRunning = false;
        sendStatus(false);
        sendLog("VPN Service Stopped");

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        if (connectionCleaner != null) {
            connectionCleaner.cancel();
            connectionCleaner = null;
        }
        if (dnsThreadPool != null) {
            dnsThreadPool.shutdownNow();
            dnsThreadPool = null;
        }

        for (TcpConnection conn : tcpConnections.values()) {
            conn.close();
        }
        tcpConnections.clear();

        if (proxyProcess != null) {
            proxyProcess.destroy();
            proxyProcess = null;
        }

        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception ignored) {}

        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                VPN_CHANNEL_ID, "VPN Connection", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, DnsttVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setContentTitle("DNSTT VPN")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_menu, "Disconnect", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, createNotification(text));
    }

    private void sendLog(String message) {
        Log.d(TAG, message);
        // [FIX] Append to static buffer
        if (logBuffer.length() > 5000) logBuffer.setLength(0);
        logBuffer.append(message).append("\n");

        Intent intent = new Intent("com.devpixl.dnstt.LOG_UPDATE");
        intent.putExtra("log", message);
        sendBroadcast(intent);
    }

    private void sendStatus(boolean running) {
        Intent intent = new Intent("com.devpixl.dnstt.STATUS_UPDATE");
        intent.putExtra("running", running);
        sendBroadcast(intent);
    }
}
