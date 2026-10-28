package app.revanced.extension.soundcloud.network;

import java.net.InetAddress;
import java.util.List;

/** What one DNS resolver found for a host. */
final class DnsLookup {
    /** The addresses, or null if none were found. */
    final List<InetAddress> addresses;
    final long ttlSeconds;
    /**
     * No address was found because the server stayed silent until a timeout, as opposed to refusing, answering with
     * an error, or answering with nothing.
     */
    final boolean unreachable;

    private DnsLookup(List<InetAddress> addresses, long ttlSeconds, boolean unreachable) {
        this.addresses = addresses;
        this.ttlSeconds = ttlSeconds;
        this.unreachable = unreachable;
    }

    static DnsLookup found(List<InetAddress> addresses, long ttlSeconds) {
        return new DnsLookup(addresses, ttlSeconds, false);
    }

    static DnsLookup notFound(boolean unreachable) {
        return new DnsLookup(null, 0, unreachable);
    }
}
