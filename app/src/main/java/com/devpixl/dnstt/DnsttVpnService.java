package com.devpixl.dnstt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DnsttVpnService extends VpnService {

    private static final String TAG = "DnsttVpnService";
    private static final String VPN_CHANNEL_ID = "dnstt_vpn_channel";
    private static final int NOTIFICATION_ID = 1;

    // Config
    private String proxyHost = "127.0.0.1";
    private int proxyPort = 1080; // The port your libdnstt.so listens on
    private String dnsServer = "8.8.8.8"; // Default DNS Target

    // State
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private ParcelFileDescriptor vpnInterface;
    private Process proxyProcess;

    // TCP State Management
    private ConcurrentHashMap<String, TcpConnection> tcpConnections = new ConcurrentHashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if ("STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        // Get config from intent
        String domain = intent.getStringExtra("domain");
        String pubKey = intent.getStringExtra("key");
        String dns = intent.getStringExtra("dns");

        if (dns != null && !dns.isEmpty()) {
            // If user provides "8.8.8.8:53", extract just the IP for internal handling if needed
            // But we pass the full string to the binary.
            String[] parts = dns.split(":");
            if (parts.length > 0) this.dnsServer = parts[0];
        }

        startVpn(domain, pubKey, dns);
        return START_STICKY;
    }

    private void startVpn(String domain, String pubKey, String dnsString) {
        if (isRunning.get()) return;
        isRunning.set(true);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Starting Tunnel..."));

        sendLog("Starting VPN Service...");

        new Thread(() -> {
            try {
                // 1. Start the dnstt binary (SOCKS5 server)
                startDnsttBinary(domain, pubKey, dnsString);

                // 2. Establish VPN Interface
                if (!establishVpnInterface()) {
                    sendLog("Error: Failed to establish VPN interface");
                    stopVpn();
                    return;
                }

                sendLog("VPN Interface Established. Tunneling traffic...");
                updateNotification("Connected to " + domain);
                sendStatus(true);

                // 3. Run Packet Loop
                runVpnLoop();

            } catch (Exception e) {
                sendLog("Critical Error: " + e.getMessage());
                Log.e(TAG, "VPN startup failed", e);
                stopVpn();
            }
        }).start();
    }

    private boolean establishVpnInterface() {
        try {
            Builder builder = new Builder();
            builder.setSession("DNSTT Tunnel");
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0); // Redirect all IPv4 traffic

            // [FIX] Block IPv6 to prevent leaks (DNSTT currently IPv4 only)
            // Routing "::/0" to the VPN interface without an IPv6 address configured
            // usually drops the packets, effectively blocking them.
            try {
                builder.addAddress("fd00::1", 128);
                builder.addRoute("::", 0);
            } catch (IllegalArgumentException ignored) {
                // IPv6 might not be supported on this device
            }

            builder.addDnsServer(dnsServer);
            builder.setMtu(1500);
            builder.setBlocking(true);

            // CRITICAL: Exclude our own app so the dnstt binary's traffic
            // goes to the real internet, not back into the VPN.
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

    private void startDnsttBinary(String domain, String pubKey, String dns) throws Exception {
        File keyFile = new File(getCacheDir(), "pub.key");
        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            fos.write(pubKey.getBytes());
        }

        String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
        File binary = new File(binaryPath);
        if (!binary.exists()) {
            sendLog("Error: libdnstt.so not found at " + binaryPath);
            throw new IOException("Binary not found");
        }

        // Attempt to make executable (often not needed for native libs but good practice if copied)
        binary.setExecutable(true);

        String[] cmd = {
            binaryPath,
            "-udp", (dns != null && !dns.isEmpty() ? dns : "8.8.8.8:53"),
            "-pubkey-file", keyFile.getAbsolutePath(),
            domain,
            proxyHost + ":" + proxyPort
        };

        sendLog("Executing binary: " + binaryPath);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        proxyProcess = pb.start();

        // Consume output in a separate thread
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("pubkey")) continue; // hide key in logs
                    sendLog("DNSTT: " + line);
                }
            } catch (Exception ignored) {}
        }).start();

        // Wait a bit for the binary to initialize
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
                        if (ipHeader.protocol == 6) { // TCP
                            processTcpPacket(ipHeader, vpnOutput);
                        } else if (ipHeader.protocol == 17) { // UDP
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
            // New Connection
            com.devpixl.dnstt.net.Socks5Client socksClient = new com.devpixl.dnstt.net.Socks5Client(
                proxyHost, proxyPort,
                ipHeader.destinationIp.getHostAddress(), tcpHeader.destinationPort
            );

            TcpConnection connection = new TcpConnection(
                ipHeader.sourceIp, tcpHeader.sourcePort,
                ipHeader.destinationIp, tcpHeader.destinationPort,
                vpnOutput, socksClient
            );

            if (connection.handleSyn(tcpHeader.sequenceNumber)) {
                tcpConnections.put(connectionId, connection);
            }

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

        // [FIX] Full Tunneling for DNS
        // Instead of bypassing VPN (protect), we tunnel DNS over TCP via SOCKS5.
        if (udpHeader.destinationPort == 53) {
            new Thread(() -> {
                try {
                    // Connect to local SOCKS5 proxy
                    Socket socksSocket = new Socket(proxyHost, proxyPort);
                    socksSocket.setSoTimeout(5000);
                    DataOutputStream out = new DataOutputStream(socksSocket.getOutputStream());
                    DataInputStream in = new DataInputStream(socksSocket.getInputStream());

                    // 1. SOCKS5 Handshake
                    out.write(new byte[]{0x05, 0x01, 0x00}); // Version 5, 1 Method, No Auth
                    byte[] authResp = new byte[2];
                    in.readFully(authResp);
                    if (authResp[0] != 0x05 || authResp[1] != 0x00) {
                        socksSocket.close();
                        return;
                    }

                    // 2. Connect Command (to 8.8.8.8:53 or whatever user configured)
                    // We hardcode 8.8.8.8 for the tunnel exit to ensure resolution works
                    byte[] ipBytes = InetAddress.getByName(dnsServer).getAddress();
                    out.write(0x05); // Ver
                    out.write(0x01); // Connect
                    out.write(0x00); // Rsv
                    out.write(0x01); // IPv4
                    out.write(ipBytes);
                    out.writeShort(53); // Port

                    byte[] connResp = new byte[10];
                    in.readFully(connResp);
                    if (connResp[1] != 0x00) {
                        socksSocket.close();
                        return;
                    }

                    // 3. Send DNS Query (TCP format: 2-byte length prefix + UDP payload)
                    out.writeShort(udpHeader.payload.length);
                    out.write(udpHeader.payload);

                    // 4. Read Response
                    int respLen = in.readUnsignedShort();
                    byte[] dnsResponse = new byte[respLen];
                    in.readFully(dnsResponse);

                    // 5. Send back to App via VPN
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
            }).start();
        }
        // Non-DNS UDP traffic is currently dropped (DNSTT limitation)
    }

    private void stopVpn() {
        isRunning.set(false);
        sendStatus(false);
        sendLog("VPN Service Stopped");

        // Cleanup connections
        for (TcpConnection conn : tcpConnections.values()) {
            conn.close();
        }
        tcpConnections.clear();

        // Kill binary
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

    // --- Helpers ---

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
