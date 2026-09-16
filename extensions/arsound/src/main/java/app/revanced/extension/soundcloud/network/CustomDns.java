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

        String mode = Settings.getDnsMode();
        long[] ttl = new long[1];
        List<InetAddress> result = null;

        if (!MODE_PLAIN.equals(mode) && !dohUrl().isEmpty()) {
            result = resolveDoh(host, ttl);
        }
        if (result == null && !MODE_DOH.equals(mode)) {
            result = resolvePlain(host, ttl);
        }
        if (result == null || result.isEmpty()) {
            Logger.printInfo(() -> "Custom DNS could not resolve " + host + ", using system DNS");
            return null;
        }

        cache.put(host, new Entry(result, ttl[0]));
        return result;
    }

    public static void clearCache() {
        cache.clear();
    }

    private static List<InetAddress> resolveDoh(String host, long[] ttl) {
        List<InetAddress> addresses = new ArrayList<>();
        for (int type : new int[]{TYPE_A, TYPE_AAAA}) {
            try {
                byte[] query = buildQuery(host, type);
                String url = dohUrl() + (dohUrl().contains("?") ? "&" : "?") + "dns="
                        + Base64.encodeToString(query, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestProperty("Accept", "application/dns-message");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                if (connection.getResponseCode() != 200) continue;
                try (InputStream input = connection.getInputStream()) {
                    addresses.addAll(parseResponse(readAll(input), ttl));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "DoH lookup failed for " + host + ": " + ex);
            }
        }
        return addresses.isEmpty() ? null : addresses;
    }

    private static List<InetAddress> resolvePlain(String host, long[] ttl) {
        for (String server : plainServers()) {
            List<InetAddress> addresses = new ArrayList<>();
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(TIMEOUT_MS);
                InetAddress serverAddress = InetAddress.getByName(server);
                for (int type : new int[]{TYPE_A, TYPE_AAAA}) {
                    byte[] query = buildQuery(host, type);
                    socket.send(new DatagramPacket(query, query.length, serverAddress, 53));
                    byte[] buffer = new byte[1500];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    byte[] data = new byte[response.getLength()];
                    System.arraycopy(buffer, 0, data, 0, data.length);
                    addresses.addAll(parseResponse(data, ttl));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "DNS " + server + " failed for " + host + ": " + ex);
            }
            if (!addresses.isEmpty()) return addresses;
        }
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

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** Resolves a host through the chosen server only, for the "Check" button. */
    public static String test(String host) {
        long start = System.currentTimeMillis();
        long[] ttl = new long[1];
        String mode = Settings.getDnsMode();
        List<InetAddress> result = null;
        String used = "";
        if (!MODE_PLAIN.equals(mode) && !dohUrl().isEmpty()) {
            result = resolveDoh(host, ttl);
            used = "DoH";
        }
        if (result == null && !MODE_DOH.equals(mode)) {
            result = resolvePlain(host, ttl);
            used = "DNS";
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
