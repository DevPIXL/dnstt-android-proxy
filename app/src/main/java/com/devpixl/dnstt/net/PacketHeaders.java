package com.devpixl.dnstt.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class PacketHeaders {

    public static class IPv4Header {
        public int version;
        public int ihl;
        public int totalLength;
        public int identification;
        public int flags;
        public int fragmentOffset;
        public int ttl;
        public byte protocol;
        public int headerChecksum;
        public InetAddress sourceIp;
        public InetAddress destinationIp;
        public byte[] payload;

        public static IPv4Header parse(byte[] data) {
            if (data.length < 20) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data);

            int versionAndIhl = buffer.get() & 0xFF;
            int version = versionAndIhl >> 4;
            int ihl = versionAndIhl & 0x0F;
            if (ihl < 5) return null;

            buffer.get(); // DSCP & ECN
            int totalLength = buffer.getShort() & 0xFFFF;
            int identification = buffer.getShort() & 0xFFFF;
            int flagsAndFragment = buffer.getShort() & 0xFFFF;
            int flags = flagsAndFragment >> 13;
            int fragmentOffset = flagsAndFragment & 0x1FFF;
            int ttl = buffer.get() & 0xFF;
            byte protocol = buffer.get();
            int headerChecksum = buffer.getShort() & 0xFFFF;

            byte[] sourceIpBytes = new byte[4];
            buffer.get(sourceIpBytes);
            byte[] destIpBytes = new byte[4];
            buffer.get(destIpBytes);

            try {
                InetAddress sourceIp = InetAddress.getByAddress(sourceIpBytes);
                InetAddress destinationIp = InetAddress.getByAddress(destIpBytes);

                // Safety check for payload length
                int payloadStart = ihl * 4;
                int payloadLength = totalLength - payloadStart;
                if (payloadLength < 0 || payloadStart + payloadLength > data.length) return null;

                byte[] payload = Arrays.copyOfRange(data, payloadStart, payloadStart + payloadLength);

                IPv4Header header = new IPv4Header();
                header.version = version;
                header.ihl = ihl;
                header.totalLength = totalLength;
                header.identification = identification;
                header.flags = flags;
                header.fragmentOffset = fragmentOffset;
                header.ttl = ttl;
                header.protocol = protocol;
                header.headerChecksum = headerChecksum;
                header.sourceIp = sourceIp;
                header.destinationIp = destinationIp;
                header.payload = payload;
                return header;

            } catch (UnknownHostException e) {
                return null;
            }
        }

        public byte[] buildPacket(TCPHeader tcpHeader) {
            byte[] ipHeaderBytes = this.toByteArray();
            byte[] tcpHeaderBytes = tcpHeader.toByteArray(this.sourceIp, this.destinationIp);
            ByteBuffer buffer = ByteBuffer.allocate(ipHeaderBytes.length + tcpHeaderBytes.length);
            buffer.put(ipHeaderBytes);
            buffer.put(tcpHeaderBytes);
            return buffer.array();
        }

        public byte[] toByteArrayForUdp() {
            int ipHeaderSize = ihl * 4;
            ByteBuffer buffer = ByteBuffer.allocate(ipHeaderSize + payload.length);

            buffer.put((byte) ((version << 4) | ihl));
            buffer.put((byte) 0);
            buffer.putShort((short) (ipHeaderSize + payload.length));
            buffer.putShort((short) identification);
            buffer.putShort((short) ((flags << 13) | fragmentOffset));
            buffer.put((byte) ttl);
            buffer.put(protocol);
            buffer.putShort((short) 0); // Checksum placeholder
            buffer.put(sourceIp.getAddress());
            buffer.put(destinationIp.getAddress());

            // Checksum
            byte[] headerOnly = Arrays.copyOf(buffer.array(), ipHeaderSize);
            int checksum = calculateChecksum(headerOnly, 0, ipHeaderSize);
            buffer.putShort(10, (short) checksum);

            buffer.position(ipHeaderSize);
            buffer.put(payload);

            return buffer.array();
        }

        private byte[] toByteArray() {
            int headerSize = ihl * 4;
            ByteBuffer buffer = ByteBuffer.allocate(headerSize);
            buffer.put((byte) ((version << 4) | ihl));
            buffer.put((byte) 0);
            buffer.putShort((short) totalLength);
            buffer.putShort((short) identification);
            buffer.putShort((short) ((flags << 13) | fragmentOffset));
            buffer.put((byte) ttl);
            buffer.put(protocol);
            buffer.putShort((short) 0);
            buffer.put(sourceIp.getAddress());
            buffer.put(destinationIp.getAddress());

            int checksum = calculateChecksum(buffer.array(), 0, headerSize);
            buffer.putShort(10, (short) checksum);
            return buffer.array();
        }
    }

    public static class TCPHeader {
        public static final int FLAG_FIN = 1;
        public static final int FLAG_SYN = 2;
        public static final int FLAG_RST = 4;
        public static final int FLAG_PSH = 8;
        public static final int FLAG_ACK = 16;
        public static final int FLAG_URG = 32;

        public int sourcePort;
        public int destinationPort;
        public long sequenceNumber;
        public long acknowledgmentNumber;
        public int dataOffset;
        public int flags;
        public int windowSize;
        public int checksum;
        public int urgentPointer;
        public byte[] payload;

        public static TCPHeader parse(byte[] data) {
            if (data.length < 20) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data);

            int sourcePort = buffer.getShort() & 0xFFFF;
            int destinationPort = buffer.getShort() & 0xFFFF;
            long sequenceNumber = buffer.getInt() & 0xFFFFFFFFL;
            long acknowledgmentNumber = buffer.getInt() & 0xFFFFFFFFL;
            int dataOffsetAndFlags = buffer.getShort() & 0xFFFF;
            int dataOffset = (dataOffsetAndFlags >> 12) & 0xF;
            int flags = dataOffsetAndFlags & 0x1FF;
            int windowSize = buffer.getShort() & 0xFFFF;
            int checksum = buffer.getShort() & 0xFFFF;
            int urgentPointer = buffer.getShort() & 0xFFFF;

            int headerLen = dataOffset * 4;
            byte[] payload = new byte[0];
            if (data.length > headerLen) {
                payload = Arrays.copyOfRange(data, headerLen, data.length);
            }

            TCPHeader header = new TCPHeader();
            header.sourcePort = sourcePort;
            header.destinationPort = destinationPort;
            header.sequenceNumber = sequenceNumber;
            header.acknowledgmentNumber = acknowledgmentNumber;
            header.dataOffset = dataOffset;
            header.flags = flags;
            header.windowSize = windowSize;
            header.checksum = checksum;
            header.urgentPointer = urgentPointer;
            header.payload = payload;
            return header;
        }

        public boolean isSYN() { return (flags & FLAG_SYN) != 0; }
        public boolean isACK() { return (flags & FLAG_ACK) != 0; }
        public boolean isFIN() { return (flags & FLAG_FIN) != 0; }
        public boolean isRST() { return (flags & FLAG_RST) != 0; }

        public byte[] toByteArray(InetAddress sourceIp, InetAddress destIp) {
            int tcpLength = (dataOffset * 4) + payload.length;
            ByteBuffer buffer = ByteBuffer.allocate(tcpLength);

            buffer.putShort((short) sourcePort);
            buffer.putShort((short) destinationPort);
            buffer.putInt((int) sequenceNumber);
            buffer.putInt((int) acknowledgmentNumber);
            buffer.putShort((short) ((dataOffset << 12) | flags));
            buffer.putShort((short) windowSize);
            buffer.putShort((short) 0); // Checksum placeholder
            buffer.putShort((short) urgentPointer);
            buffer.put(payload);

            byte[] array = buffer.array();
            byte[] pseudoHeader = createPseudoHeader(sourceIp, destIp, tcpLength, (byte) 6);

            // Combine for checksum calculation
            byte[] forChecksum = new byte[pseudoHeader.length + array.length];
            System.arraycopy(pseudoHeader, 0, forChecksum, 0, pseudoHeader.length);
            System.arraycopy(array, 0, forChecksum, pseudoHeader.length, array.length);

            int checksum = calculateChecksum(forChecksum, 0, forChecksum.length);
            buffer.putShort(16, (short) checksum);

            return buffer.array();
        }
    }

    public static class UDPHeader {
        public int sourcePort;
        public int destinationPort;
        public int length;
        public int checksum;
        public byte[] payload;

        public static UDPHeader parse(byte[] data) {
            if (data.length < 8) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data);

            UDPHeader header = new UDPHeader();
            header.sourcePort = buffer.getShort() & 0xFFFF;
            header.destinationPort = buffer.getShort() & 0xFFFF;
            header.length = buffer.getShort() & 0xFFFF;
            header.checksum = buffer.getShort() & 0xFFFF;

            if (data.length > 8) {
                header.payload = Arrays.copyOfRange(data, 8, Math.min(header.length, data.length));
            } else {
                header.payload = new byte[0];
            }
            return header;
        }

        // Helper to build a full UDP packet bytes used in VpnService response
        public static byte[] buildUdpPacket(UDPHeader udp, IPv4Header ip) {
            // Build UDP part
            ByteBuffer udpBuf = ByteBuffer.allocate(8 + udp.payload.length);
            udpBuf.putShort((short) udp.sourcePort);
            udpBuf.putShort((short) udp.destinationPort);
            udpBuf.putShort((short) (8 + udp.payload.length));
            udpBuf.putShort((short) 0); // Checksum
            udpBuf.put(udp.payload);

            byte[] udpBytes = udpBuf.array();
            byte[] pseudo = createPseudoHeader(ip.sourceIp, ip.destinationIp, udpBytes.length, (byte) 17);

            byte[] forCheck = new byte[pseudo.length + udpBytes.length];
            System.arraycopy(pseudo, 0, forCheck, 0, pseudo.length);
            System.arraycopy(udpBytes, 0, forCheck, pseudo.length, udpBytes.length);

            int checksum = calculateChecksum(forCheck, 0, forCheck.length);
            udpBuf.putShort(6, (short) checksum);

            // Assign to IP payload and build full IP packet
            ip.payload = udpBuf.array();
            return ip.toByteArrayForUdp();
        }
    }

    private static byte[] createPseudoHeader(InetAddress src, InetAddress dest, int length, byte protocol) {
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.put(src.getAddress());
        buffer.put(dest.getAddress());
        buffer.put((byte) 0);
        buffer.put(protocol);
        buffer.putShort((short) length);
        return buffer.array();
    }

    private static int calculateChecksum(byte[] data, int offset, int length) {
        int sum = 0;
        int i = offset;
        while (i < length - 1) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            i += 2;
        }
        if (length % 2 != 0) {
            sum += (data[length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }
}
