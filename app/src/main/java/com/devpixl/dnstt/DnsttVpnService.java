package com.devpixl.dnstt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.devpixl.dnstt.net.PacketHeaders;
import com.devpixl.dnstt.net.TcpConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
    private String dnsServer = "8.8.8.8";

    // State
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
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

        // Get config from intent (passed from MainActivity)
        String domain = intent.getStringExtra("domain");
        String pubKey = intent.getStringExtra("key");
        String dns = intent.getStringExtra("dns");
        if (dns != null && !dns.isEmpty()) {
            this.dnsServer = dns.split(":")[0]; // Extract IP if format is IP:Port
        }

        startVpn(domain, pubKey, intent.getStringExtra("dns")); // Pass full DNS string to binary
        return START_STICKY;
    }

    private void startVpn(String domain, String pubKey, String dnsString) {
        if (isRunning.get()) return;
        isRunning.set(true);

        // 1. Start Foreground Notification
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."));

        new Thread(() -> {
            try {
                // 2. Start the dnstt binary (SOCKS5 server)
                startDnsttBinary(domain, pubKey, dnsString);

                // 3. Establish VPN Interface
                if (!establishVpnInterface()) {
                    Log.e(TAG, "Failed to establish VPN");
                    stopVpn();
                    return;
                }

                updateNotification("Connected to " + domain);

                // 4. Run Packet Loop
                runVpnLoop();

            } catch (Exception e) {
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
            builder.addRoute("0.0.0.0", 0); // Redirect all traffic
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
            Log.e(TAG, "Error building VPN interface", e);
            return false;
        }
    }

    private void startDnsttBinary(String domain, String pubKey, String dns) throws Exception {
        File keyFile = new File(getCacheDir(), "pub.key");
        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            fos.write(pubKey.getBytes());
        }

        String binaryPath = getApplicationInfo().nativeLibraryDir + "/libdnstt.so";
        String[] cmd = {
            binaryPath,
            "-udp", (dns != null ? dns : "8.8.8.8:53"),
            "-pubkey-file", keyFile.getAbsolutePath(),
            domain,
            proxyHost + ":" + proxyPort
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        proxyProcess = pb.start();

        // Consume output in a separate thread to prevent blocking
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proxyProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d("DNSTT_BIN", line);
                }
            } catch (Exception ignored) {}
        }).start();

        // Wait a bit for the binary to initialize socket
        Thread.sleep(500);
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
            Log.e(TAG, "VPN Loop Error", e);
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
                Log.d(TAG, "New TCP: " + connectionId);
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

        // Only handle DNS queries (Port 53)
        // Note: DNSTT itself tunnels TCP. For UDP (like DNS), we usually forward to a real DNS server
        // bypassing the tunnel to avoid loops, or forward to 8.8.8.8.
        if (udpHeader.destinationPort == 53) {
            new Thread(() -> {
                try {
                    DatagramSocket dnsSocket = new DatagramSocket();
                    // PROTECT is crucial: bypasses VPN to send this packet to the real internet
                    protect(dnsSocket);

                    InetAddress dnsAddr = InetAddress.getByName(dnsServer);
                    DatagramPacket packet = new DatagramPacket(udpHeader.payload, udpHeader.payload.length, dnsAddr, 53);
                    dnsSocket.send(packet);

                    byte[] buf = new byte[4096];
                    DatagramPacket response = new DatagramPacket(buf, buf.length);
                    dnsSocket.setSoTimeout(4000);
                    dnsSocket.receive(response);

                    // Send response back to app via VPN
                    byte[] responseData = new byte[response.getLength()];
                    System.arraycopy(buf, 0, responseData, 0, response.getLength());

                    PacketHeaders.UDPHeader respUdp = new PacketHeaders.UDPHeader();
                    respUdp.sourcePort = 53;
                    respUdp.destinationPort = udpHeader.sourcePort;
                    respUdp.payload = responseData;

                    // Swap Source/Dest IP for response
                    PacketHeaders.IPv4Header respIp = new PacketHeaders.IPv4Header();
                    respIp.version = 4;
                    respIp.ihl = 5;
                    respIp.ttl = 64;
                    respIp.protocol = 17;
                    respIp.sourceIp = ipHeader.destinationIp; // Server is now source
                    respIp.destinationIp = ipHeader.sourceIp; // App is destination
                    respIp.identification = ipHeader.identification + 1;

                    // Helper to build full packet
                    byte[] rawResponse = PacketHeaders.UDPHeader.buildUdpPacket(respUdp, respIp);

                    synchronized (vpnOutput) {
                        vpnOutput.write(rawResponse);
                        vpnOutput.flush();
                    }
                    dnsSocket.close();

                } catch (Exception e) {
                    Log.e(TAG, "DNS Forwarding failed", e);
                }
            }).start();
        }
    }

    private void stopVpn() {
        isRunning.set(false);

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

        // Close Interface
        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception ignored) {}

        stopForeground(true);
        stopSelf();
    }

    // --- Notification Helpers ---
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

        // Add Disconnect Action
        Intent stopIntent = new Intent(this, DnsttVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, VPN_CHANNEL_ID)
                .setContentTitle("DNSTT VPN")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_app_icon) // Ensure this exists
                .setContentIntent(pi)
                .addAction(R.drawable.ic_menu, "Disconnect", stopPi)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, createNotification(text));
    }
}
