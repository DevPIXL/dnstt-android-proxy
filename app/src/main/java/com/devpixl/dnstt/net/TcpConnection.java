package com.devpixl.dnstt.net;

import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

public class TcpConnection {
    private static final String TAG = "TcpConnection";
    private static final long INITIAL_SEQ = 1000L;

    public final InetAddress sourceIp;
    public final int sourcePort;
    public final InetAddress destIp;
    public final int destPort;
    private final FileOutputStream vpnOutput;
    private final Socks5Client socks5Client;

    private final AtomicLong ourSeq = new AtomicLong(INITIAL_SEQ);
    private long clientSeq = 0;

    private enum State { SYN_RECEIVED, ESTABLISHED, FIN_WAIT, CLOSED }
    private volatile State state = State.SYN_RECEIVED;
    private final Object outputLock = new Object();

    public TcpConnection(InetAddress sourceIp, int sourcePort,
                         InetAddress destIp, int destPort,
                         FileOutputStream vpnOutput, Socks5Client client) {
        this.sourceIp = sourceIp;
        this.sourcePort = sourcePort;
        this.destIp = destIp;
        this.destPort = destPort;
        this.vpnOutput = vpnOutput;
        this.socks5Client = client;
    }

    public boolean handleSyn(long clientSeqNum) {
        this.clientSeq = clientSeqNum + 1;

        socks5Client.onDataReceived = this::sendDataToClient;

        if (!socks5Client.connect()) {
            return false;
        }

        sendSynAck();
        return true;
    }

    public void handleAck(long seqNum, long ackNum, byte[] payload) {
        if (state == State.SYN_RECEIVED) {
            if (ackNum == ourSeq.get() + 1) {
                ourSeq.incrementAndGet();
                state = State.ESTABLISHED;
            }
        }

        if (state == State.ESTABLISHED && payload.length > 0) {
            socks5Client.send(payload);
            clientSeq = seqNum + payload.length;
            sendAck();
        }
    }

    public void handleFin(long seqNum) {
        state = State.FIN_WAIT;
        clientSeq = seqNum + 1;
        sendFinAck();
        socks5Client.disconnect();
        state = State.CLOSED;
    }

    public void close() {
        socks5Client.disconnect();
        state = State.CLOSED;
    }

    private void sendSynAck() {
        sendTcpPacket(PacketHeaders.TCPHeader.FLAG_SYN | PacketHeaders.TCPHeader.FLAG_ACK, new byte[0]);
    }

    private void sendAck() {
        sendTcpPacket(PacketHeaders.TCPHeader.FLAG_ACK, new byte[0]);
    }

    private void sendFinAck() {
        sendTcpPacket(PacketHeaders.TCPHeader.FLAG_FIN | PacketHeaders.TCPHeader.FLAG_ACK, new byte[0]);
        ourSeq.incrementAndGet();
    }

    private void sendDataToClient(byte[] data) {
        if (state != State.ESTABLISHED) return;

        sendTcpPacket(PacketHeaders.TCPHeader.FLAG_ACK | PacketHeaders.TCPHeader.FLAG_PSH, data);
        ourSeq.addAndGet(data.length);
    }

    private void sendTcpPacket(int flags, byte[] payload) {
        PacketHeaders.TCPHeader tcpHeader = new PacketHeaders.TCPHeader();
        tcpHeader.sourcePort = destPort;
        tcpHeader.destinationPort = sourcePort;
        tcpHeader.sequenceNumber = ourSeq.get();
        tcpHeader.acknowledgmentNumber = clientSeq;
        tcpHeader.dataOffset = 5;
        tcpHeader.flags = flags;
        tcpHeader.windowSize = 65535;
        tcpHeader.urgentPointer = 0;
        tcpHeader.payload = payload;

        PacketHeaders.IPv4Header ipHeader = new PacketHeaders.IPv4Header();
        ipHeader.version = 4;
        ipHeader.ihl = 5;
        ipHeader.totalLength = 20 + 20 + payload.length;
        ipHeader.identification = (int) (System.currentTimeMillis() & 0xFFFF);
        ipHeader.flags = 0;
        ipHeader.fragmentOffset = 0;
        ipHeader.ttl = 64;
        ipHeader.protocol = 6;
        ipHeader.sourceIp = destIp;
        ipHeader.destinationIp = sourceIp;

        byte[] packet = ipHeader.buildPacket(tcpHeader);

        synchronized (outputLock) {
            try {
                vpnOutput.write(packet);
                vpnOutput.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to VPN", e);
            }
        }
    }
}
