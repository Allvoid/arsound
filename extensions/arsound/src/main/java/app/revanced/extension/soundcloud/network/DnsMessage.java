package app.revanced.extension.soundcloud.network;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A DNS message (RFC 1035): the question for the address of a host and the addresses in an answer. Pure, no Android. */
final class DnsMessage {
    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;

    private static final int HEADER_BYTES = 12;

    private DnsMessage() {
    }

    /** The smallest time to live of the address records read so far; 0 until the first one. */
    static final class Ttl {
        private long seconds;

        void add(long recordTtl) {
            seconds = seconds == 0 ? recordTtl : Math.min(seconds, recordTtl);
        }

        long seconds() {
            return seconds;
        }
    }

    /** The question "which addresses of this type does the host have", with a random id. */
    static byte[] buildQuery(String host, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        out.write(id >> 8);
        out.write(id);
        out.write(0x01); // Recursion desired.
        out.write(0x00);
        out.write(0x00);
        out.write(0x01); // One question.
        for (int i = 0; i < 6; i++) out.write(0x00);
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0x00);
        out.write(type >> 8);
        out.write(type);
        out.write(0x00);
        out.write(0x01); // Class IN.
        return out.toByteArray();
    }

    /**
     * The A and AAAA addresses of an answer. Other records are skipped.
     *
     * @param ttl Takes the time to live of each address record found.
     */
    static List<InetAddress> parseResponse(byte[] data, Ttl ttl) throws UnknownHostException {
        List<InetAddress> addresses = new ArrayList<>();
        if (data.length < HEADER_BYTES) return addresses;
        int questions = u16(data, 4);
        int answers = u16(data, 6);
        int offset = HEADER_BYTES;
        for (int i = 0; i < questions; i++) offset = skipName(data, offset) + 4;
        for (int i = 0; i < answers && offset < data.length; i++) {
            offset = skipName(data, offset);
            int type = u16(data, offset);
            long recordTtl = ((long) u16(data, offset + 4) << 16) | u16(data, offset + 6);
            int length = u16(data, offset + 8);
            offset += 10;
            if ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16)) {
                byte[] address = new byte[length];
                System.arraycopy(data, offset, address, 0, length);
                addresses.add(InetAddress.getByAddress(address));
                ttl.add(recordTtl);
            }
            offset += length;
        }
        return addresses;
    }

    private static int skipName(byte[] data, int offset) {
        while (offset < data.length) {
            int length = data[offset] & 0xff;
            if (length == 0) return offset + 1;
            if ((length & 0xc0) == 0xc0) return offset + 2; // Compression pointer.
            offset += length + 1;
        }
        return offset;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
