package app.revanced.extension.soundcloud.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;

/** Plain DNS over UDP, port 53, asking the servers one after another. */
final class PlainDnsResolver extends DnsResolver {
    /** All plain DNS servers together get this long, not {@link #TIMEOUT_MS} each. */
    private static final int DEADLINE_MS = 4_000;
    private static final int DNS_PORT = 53;

    @Override
    String label() {
        return "DNS";
    }

    @Override
    boolean takesPartIn(String mode) {
        return !CustomDns.MODE_DOH.equals(mode);
    }

    @Override
    DnsLookup resolve(String host) {
        boolean answered = false;
        boolean timedOut = false;
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        for (String server : CustomDns.plainServers()) {
            if (System.currentTimeMillis() >= deadline) break;
            List<InetAddress> addresses = new ArrayList<>();
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress serverAddress = InetAddress.getByName(server);
                for (int type : new int[]{DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA}) {
                    // A zero timeout would wait for ever, so the last milliseconds are not worth a query.
                    int wait = (int) Math.min(TIMEOUT_MS, deadline - System.currentTimeMillis());
                    if (wait <= 0) break;
                    socket.setSoTimeout(wait);
                    byte[] query = DnsMessage.buildQuery(host, type);
                    socket.send(new DatagramPacket(query, query.length, serverAddress, DNS_PORT));
                    byte[] buffer = new byte[1500];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    // Only the answer to this query, from the server asked: a stray or forged packet that reached the
                    // port first is skipped, and the wait goes on for the rest of the time.
                    while (true) {
                        socket.receive(response);
                        if (serverAddress.equals(response.getAddress()) && response.getLength() >= 2
                                && buffer[0] == query[0] && buffer[1] == query[1]) {
                            break;
                        }
                        int left = (int) (deadline - System.currentTimeMillis());
                        if (left <= 0) throw new SocketTimeoutException("No answer to the query");
                        socket.setSoTimeout(left);
                        response.setLength(buffer.length);
                    }
                    answered = true;
                    byte[] data = new byte[response.getLength()];
                    System.arraycopy(buffer, 0, data, 0, data.length);
                    addresses.addAll(DnsMessage.parseResponse(data, ttl));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "DNS " + server + " failed for " + host + ": " + ex);
                if (ex instanceof SocketTimeoutException) timedOut = true;
            }
            if (!addresses.isEmpty()) return DnsLookup.found(addresses, ttl.seconds());
        }
        return DnsLookup.notFound(timedOut && !answered);
    }
}
