package app.revanced.extension.soundcloud.network;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Resolves host names through a chosen DNS server instead of the system one, for this app only.
 * <p>
 * Modes: DNS-over-HTTPS (the query goes inside HTTPS and cannot be seen or replaced by the provider)
 * and plain DNS over UDP. "Auto" tries DoH first, then plain DNS. If the chosen server fails, the
 * system resolver is used, so the option never breaks the network.
 */
public final class CustomDns {
    public static final String PRESET_XBOX = "xbox";
    public static final String PRESET_CUSTOM = "custom";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_DOH = "doh";
    public static final String MODE_PLAIN = "plain";

    public static final String XBOX_DOH = "https://xbox-dns.ru/dns-query";
    public static final String[] XBOX_SERVERS = {"111.88.96.50", "111.88.96.51"};
    public static final String XBOX_DOH_HOST = "xbox-dns.ru";

    private static final int TIMEOUT_MS = 3_000;
    /** All plain DNS servers together get this long, not {@link #TIMEOUT_MS} each. */
    private static final int PLAIN_DEADLINE_MS = 4_000;
    /** The A and AAAA answers of a DoH server together get this long. */
    private static final int DOH_DEADLINE_MS = 6_000;
    /** A DNS message of a host name is far smaller; anything bigger is not a DoH answer. */
    private static final int MAX_DOH_BYTES = 4_096;
    private static final int MAX_DOH_READ_BYTES = 64 * 1024;
    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;

    private static final class Entry {
        final List<InetAddress> addresses;
        final long expiresAt;

        Entry(List<InetAddress> addresses, long ttlSeconds) {
            this.addresses = addresses;
            this.expiresAt = System.currentTimeMillis() + Math.max(30, Math.min(ttlSeconds, 3600)) * 1000;
        }
    }

    private static final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** Hosts the custom servers failed for, until when: the system resolver answers them without the long wait. */
    private static final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private static final long FAILURE_KEPT_MS = 30_000;
    private static final int MAX_CACHED_HOSTS = 500;
    /**
     * Until when each kind of server is skipped after it timed out without any answer. The marker per host above does
     * not help here: every new host would wait for the same silent server again. Short, so a network that comes
     * back is not left on the system resolver for long.
     */
    private static volatile long dohDownUntil;
    private static volatile long plainDownUntil;
    private static final long SERVER_DOWN_KEPT_MS = 30_000;

    private CustomDns() {
    }

    public static String dohUrl() {
        return PRESET_CUSTOM.equals(Settings.getDnsPreset()) ? Settings.getCustomDohUrl() : XBOX_DOH;
    }

    public static String[] plainServers() {
        if (!PRESET_CUSTOM.equals(Settings.getDnsPreset())) return XBOX_SERVERS;
        List<String> servers = new ArrayList<>();
        for (String server : Settings.getCustomDnsServers().split("[,\\s]+")) {
            if (!server.trim().isEmpty()) servers.add(server.trim());
        }
        return servers.toArray(new String[0]);
    }

    /**
     * @return The addresses of the host, or null to use the system resolver.
     */
    public static List<InetAddress> lookup(String host) {
        if (!Settings.isCustomDnsEnabled() || host == null) return null;
        // Literal addresses need no resolving.
        if (host.matches("[0-9.]+") || host.contains(":")) return null;

        Entry cached = cache.get(host);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.addresses;
        // Unreachable servers cost several seconds per attempt; every new connection must not pay them again.
        Long failed = failedUntil.get(host);
        if (failed != null && failed > System.currentTimeMillis()) return null;

        String mode = Settings.getDnsMode();
        long[] ttl = new long[1];
        boolean[] unreachable = new boolean[1];
        List<InetAddress> result = null;

        if (!MODE_PLAIN.equals(mode) && !dohUrl().isEmpty() && dohDownUntil <= System.currentTimeMillis()) {
            result = resolveDoh(host, ttl, unreachable);
            if (unreachable[0]) dohDownUntil = System.currentTimeMillis() + SERVER_DOWN_KEPT_MS;
        }
        if (result == null && !MODE_DOH.equals(mode) && plainDownUntil <= System.currentTimeMillis()) {
            result = resolvePlain(host, ttl, unreachable);
            if (unreachable[0]) plainDownUntil = System.currentTimeMillis() + SERVER_DOWN_KEPT_MS;
        }
        if (result == null || result.isEmpty()) {
            Logger.printInfo(() -> "Custom DNS could not resolve " + host + ", using system DNS");
            if (failedUntil.size() >= MAX_CACHED_HOSTS) failedUntil.clear();
            failedUntil.put(host, System.currentTimeMillis() + FAILURE_KEPT_MS);
            return null;
        }

        // Hosts of CDN shards come in thousands over a long session: the cache does not grow for ever.
        if (cache.size() >= MAX_CACHED_HOSTS) cache.clear();
        failedUntil.remove(host);
        cache.put(host, new Entry(result, ttl[0]));
        return result;
    }

    public static void clearCache() {
        failedUntil.clear();
        cache.clear();
        dohDownUntil = 0;
        plainDownUntil = 0;
    }

    /**
     * @param unreachable Set to true when no address was found because the server stayed silent until a timeout,
     *                    as opposed to refusing, answering with an error, or answering with nothing.
     */
    private static List<InetAddress> resolveDoh(String host, long[] ttl, boolean[] unreachable) {
        List<InetAddress> addresses = new ArrayList<>();
        boolean timedOut = false;
        long deadline = System.currentTimeMillis() + DOH_DEADLINE_MS;
        for (int type : new int[]{TYPE_A, TYPE_AAAA}) {
            if (System.currentTimeMillis() > deadline) break;
            HttpURLConnection connection = null;
            boolean complete = false;
            try {
                byte[] query = buildQuery(host, type);
                String url = dohUrl() + (dohUrl().contains("?") ? "&" : "?") + "dns="
                        + Base64.encodeToString(query, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestProperty("Accept", "application/dns-message");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                if (connection.getResponseCode() != 200) {
                    // The server answered, but not with an address; the second query would get the same.
                    Logger.printInfo(() -> "DoH server answered with an error for " + host);
                    break;
                }
                if (connection.getContentLength() > MAX_DOH_BYTES) throw new java.io.IOException("DoH answer is too big");
                try (InputStream input = connection.getInputStream()) {
                    addresses.addAll(parseResponse(readAll(input, deadline), ttl));
                }
                complete = true;
            } catch (java.io.IOException ex) {
                // A timeout or a refused connection: the second query would wait for the same.
                Logger.printInfo(() -> "DoH lookup failed for " + host + ": " + ex);
                timedOut = ex instanceof java.net.SocketTimeoutException;
                break;
            } catch (Exception ex) {
                Logger.printInfo(() -> "DoH lookup failed for " + host + ": " + ex);
            } finally {
                // A fully read answer leaves the connection for the next query; anything else must not keep its socket
                // until the garbage collector.
                if (!complete && connection != null) connection.disconnect();
            }
        }
        unreachable[0] = timedOut && addresses.isEmpty();
        return addresses.isEmpty() ? null : addresses;
    }

    /** @param unreachable Set to true when no server answered at all and one of them stayed silent until a timeout. */
    private static List<InetAddress> resolvePlain(String host, long[] ttl, boolean[] unreachable) {
        boolean answered = false;
        boolean timedOut = false;
        long deadline = System.currentTimeMillis() + PLAIN_DEADLINE_MS;
        for (String server : plainServers()) {
            if (System.currentTimeMillis() >= deadline) break;
            List<InetAddress> addresses = new ArrayList<>();
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress serverAddress = InetAddress.getByName(server);
                for (int type : new int[]{TYPE_A, TYPE_AAAA}) {
                    // A zero timeout would wait for ever, so the last milliseconds are not worth a query.
                    int wait = (int) Math.min(TIMEOUT_MS, deadline - System.currentTimeMillis());
                    if (wait <= 0) break;
                    socket.setSoTimeout(wait);
                    byte[] query = buildQuery(host, type);
                    socket.send(new DatagramPacket(query, query.length, serverAddress, 53));
                    byte[] buffer = new byte[1500];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    answered = true;
                    byte[] data = new byte[response.getLength()];
                    System.arraycopy(buffer, 0, data, 0, data.length);
                    addresses.addAll(parseResponse(data, ttl));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "DNS " + server + " failed for " + host + ": " + ex);
                if (ex instanceof java.net.SocketTimeoutException) timedOut = true;
            }
            if (!addresses.isEmpty()) {
                unreachable[0] = false;
                return addresses;
            }
        }
        unreachable[0] = timedOut && !answered;
        return null;
    }

    private static byte[] buildQuery(String host, int type) {
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
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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

    private static List<InetAddress> parseResponse(byte[] data, long[] ttl) throws UnknownHostException {
        List<InetAddress> addresses = new ArrayList<>();
        if (data.length < 12) return addresses;
        int questions = u16(data, 4);
        int answers = u16(data, 6);
        int offset = 12;
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
                ttl[0] = ttl[0] == 0 ? recordTtl : Math.min(ttl[0], recordTtl);
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

    /**
     * Reads the answer of a server. A reply that is too big or too slow is cut: the per-read timeout does not
     * stop a server that sends a byte at a time.
     */
    private static byte[] readAll(InputStream input, long deadline) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_DOH_READ_BYTES) throw new java.io.IOException("DoH answer is too big");
            if (System.currentTimeMillis() > deadline) throw new java.net.SocketTimeoutException("DoH answer is too slow");
        }
        return out.toByteArray();
    }

    /** Resolves a host through the chosen server only, for the "Check" button. */
    public static String test(String host) {
        long start = System.currentTimeMillis();
        long[] ttl = new long[1];
        boolean[] unreachable = new boolean[1];
        String mode = Settings.getDnsMode();
        List<InetAddress> result = null;
        String used = "";
        if (!MODE_PLAIN.equals(mode) && !dohUrl().isEmpty()) {
            result = resolveDoh(host, ttl, unreachable);
            used = "DoH";
            if (result != null) dohDownUntil = 0;
        }
        if (result == null && !MODE_DOH.equals(mode)) {
            result = resolvePlain(host, ttl, unreachable);
            used = "DNS";
            if (result != null) plainDownUntil = 0;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (result == null) return null;
        StringBuilder text = new StringBuilder(used + ", " + elapsed + " ms: ");
        for (int i = 0; i < Math.min(result.size(), 3); i++) {
            if (i > 0) text.append(", ");
            text.append(result.get(i).getHostAddress());
        }
        return text.toString();
    }
}
