package app.revanced.extension.soundcloud.network;

/**
 * One way to ask a DNS server: DNS-over-HTTPS or plain UDP. {@link CustomDns} tries the resolvers its mode allows
 * in turn. A resolver whose server stayed silent is skipped for a while: the marker per host in {@link CustomDns}
 * does not help there, because every new host would wait for the same silent server again. The pause is short, so a
 * network that comes back is not left on the system resolver for long.
 */
abstract class DnsResolver {
    private static final long SERVER_DOWN_KEPT_MS = 30_000;
    /** A query to one server gets this long. */
    static final int TIMEOUT_MS = 3_000;

    private volatile long downUntil;

    /** "DoH" or "DNS", for the check button. */
    abstract String label();

    /** Whether the user's DNS mode ({@link CustomDns#MODE_AUTO} and the others) lets this resolver answer. */
    abstract boolean takesPartIn(String mode);

    /** Whether there is a server to ask. */
    boolean isConfigured() {
        return true;
    }

    /** Asks the server for the addresses of the host. Blocks. */
    abstract DnsLookup resolve(String host);

    final boolean isSkipped() {
        return downUntil > System.currentTimeMillis();
    }

    final void markDown() {
        downUntil = System.currentTimeMillis() + SERVER_DOWN_KEPT_MS;
    }

    final void markUp() {
        downUntil = 0;
    }
}
