package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.List;

import org.junit.Test;

public class DnsMessageTest {
    @Test
    public void queryAsksOneQuestionForTheHost() {
        byte[] query = DnsMessage.buildQuery("api.example.com", DnsMessage.TYPE_A);
        // Id (2), flags with recursion desired, one question, no other records.
        assertEquals(0x01, query[2]);
        assertEquals(0x00, query[3]);
        assertEquals(1, query[5]);
        byte[] rest = new byte[query.length - 12];
        System.arraycopy(query, 12, rest, 0, rest.length);
        byte[] expected = new byte[]{3, 'a', 'p', 'i', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1};
        assertArrayEquals(expected, rest);
    }

    @Test
    public void queryForIpv6HasTheAaaaType() {
        byte[] query = DnsMessage.buildQuery("a.b", DnsMessage.TYPE_AAAA);
        assertEquals(28, query[query.length - 3]);
    }

    /** The question for "a.b", changed into an answer with the given records after it. */
    private static byte[] answer(byte[]... records) {
        byte[] query = DnsMessage.buildQuery("a.b", DnsMessage.TYPE_A);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(query, 0, query.length);
        for (byte[] record : records) out.write(record, 0, record.length);
        byte[] full = out.toByteArray();
        full[2] = (byte) 0x81;
        full[3] = (byte) 0x80;
        full[7] = (byte) records.length;
        return full;
    }

    private static byte[] record(int type, long ttl, byte... address) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xC0); // The name is a pointer to the question.
        out.write(0x0C);
        out.write(type >> 8);
        out.write(type);
        out.write(0);
        out.write(1);
        out.write((int) (ttl >> 24));
        out.write((int) (ttl >> 16));
        out.write((int) (ttl >> 8));
        out.write((int) ttl);
        out.write(address.length >> 8);
        out.write(address.length);
        out.write(address, 0, address.length);
        return out.toByteArray();
    }

    @Test
    public void addressesAndTheSmallestTtlAreRead() throws Exception {
        byte[] data = answer(
                record(DnsMessage.TYPE_A, 300, (byte) 1, (byte) 2, (byte) 3, (byte) 4),
                record(DnsMessage.TYPE_A, 120, (byte) 5, (byte) 6, (byte) 7, (byte) 8));
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        List<InetAddress> addresses = DnsMessage.parseResponse(data, ttl);
        assertEquals(2, addresses.size());
        assertEquals("1.2.3.4", addresses.get(0).getHostAddress());
        assertEquals("5.6.7.8", addresses.get(1).getHostAddress());
        assertEquals(120, ttl.seconds());
    }

    @Test
    public void ipv6AddressesAreRead() throws Exception {
        byte[] v6 = new byte[16];
        v6[15] = 1;
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        List<InetAddress> addresses = DnsMessage.parseResponse(answer(record(DnsMessage.TYPE_AAAA, 60, v6)), ttl);
        assertEquals(1, addresses.size());
        assertEquals("0:0:0:0:0:0:0:1", addresses.get(0).getHostAddress());
        assertEquals(60, ttl.seconds());
    }

    @Test
    public void otherRecordsAreSkipped() throws Exception {
        // A record of type 5 with 3 bytes of data, then an address.
        byte[] data = answer(record(5, 100, (byte) 1, (byte) 2, (byte) 3),
                record(DnsMessage.TYPE_A, 200, (byte) 9, (byte) 9, (byte) 9, (byte) 9));
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        List<InetAddress> addresses = DnsMessage.parseResponse(data, ttl);
        assertEquals(1, addresses.size());
        assertEquals(200, ttl.seconds());
    }

    @Test
    public void aTooShortAnswerHasNoAddresses() throws Exception {
        assertTrue(DnsMessage.parseResponse(new byte[5], new DnsMessage.Ttl()).isEmpty());
    }

    @Test
    public void ttlOfTwoAnswersKeepsTheSmallest() throws Exception {
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        DnsMessage.parseResponse(answer(record(DnsMessage.TYPE_A, 300, (byte) 1, (byte) 1, (byte) 1, (byte) 1)), ttl);
        DnsMessage.parseResponse(answer(record(DnsMessage.TYPE_A, 90, (byte) 2, (byte) 2, (byte) 2, (byte) 2)), ttl);
        assertEquals(90, ttl.seconds());
    }
}
