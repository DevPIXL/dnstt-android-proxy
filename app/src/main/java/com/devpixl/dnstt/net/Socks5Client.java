package com.devpixl.dnstt.net;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

public class Socks5Client {
    private static final String TAG = "Socks5Client";
    private static final byte SOCKS_VERSION = 5;
    private static final byte AUTH_METHOD_NONE = 0;
    private static final byte CMD_CONNECT = 1;
    private static final byte ADDR_TYPE_DOMAIN = 3;

    private final String proxyHost;
    private final int proxyPort;
    private final String targetHost;
    private final int targetPort;

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private volatile boolean shouldRun = true;

    public interface DataCallback {
        void onDataReceived(byte[] data);
    }

    public DataCallback onDataReceived;

    public Socks5Client(String proxyHost, int proxyPort, String targetHost, int targetPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public boolean connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), 30000);
            socket.setSoTimeout(60000);
            socket.setTcpNoDelay(true);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();

            if (!handshake()) {
                Log.e(TAG, "SOCKS5 handshake failed");
                disconnect();
                return false;
            }

            if (!sendCommand()) {
                Log.e(TAG, "SOCKS5 command failed");
                disconnect();
                return false;
            }

            // Start reading in background
            new Thread(this::readLoop).start();
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private boolean handshake() throws IOException {
        outputStream.write(new byte[]{SOCKS_VERSION, 1, AUTH_METHOD_NONE});
        outputStream.flush();

        byte[] response = new byte[2];
        int read = inputStream.read(response);
        return read == 2 && response[0] == SOCKS_VERSION && response[1] == AUTH_METHOD_NONE;
    }

    private boolean sendCommand() throws IOException {
        byte[] domainBytes = targetHost.getBytes();
        int len = 7 + domainBytes.length;
        // 1(ver) + 1(cmd) + 1(rsv) + 1(type) + 1(len) + domain + 2(port)

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        buffer.write(SOCKS_VERSION);
        buffer.write(CMD_CONNECT);
        buffer.write(0); // Reserved
        buffer.write(ADDR_TYPE_DOMAIN);
        buffer.write(domainBytes.length);
        buffer.write(domainBytes);
        buffer.write((targetPort >> 8) & 0xFF);
        buffer.write(targetPort & 0xFF);

        outputStream.write(buffer.toByteArray());
        outputStream.flush();

        byte[] response = new byte[10]; // Minimal response read
        int read = inputStream.read(response);

        // response[1] == 0 means SUCCESS
        return read >= 4 && response[0] == SOCKS_VERSION && response[1] == 0;
    }

    public void send(byte[] data) {
        try {
            if (outputStream != null) {
                outputStream.write(data);
                outputStream.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Send failed", e);
            disconnect();
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[32767];
        try {
            while (shouldRun && socket != null && !socket.isClosed()) {
                int read = inputStream.read(buffer);
                if (read > 0) {
                    if (onDataReceived != null) {
                        onDataReceived.onDataReceived(Arrays.copyOf(buffer, read));
                    }
                } else if (read == -1) {
                    break;
                }
            }
        } catch (IOException e) {
            if (shouldRun) Log.e(TAG, "Read loop error", e);
        } finally {
            disconnect();
        }
    }

    public void disconnect() {
        shouldRun = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }
}
