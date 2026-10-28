package app.revanced.extension.soundcloud.network;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;

/** DNS over HTTPS: the query goes inside HTTPS and cannot be seen or replaced by the provider. */
final class DohResolver extends DnsResolver {
    /** The A and AAAA answers of a DoH server together get this long. */
    private static final int DEADLINE_MS = 6_000;
    /** A DNS message of a host name is far smaller; anything bigger is not a DoH answer. */
    private static final int MAX_BYTES = 4_096;
    private static final int MAX_READ_BYTES = 64 * 1024;

    @Override
    String label() {
        return "DoH";
    }

    @Override
    boolean takesPartIn(String mode) {
        return !CustomDns.MODE_PLAIN.equals(mode);
    }

    @Override
    boolean isConfigured() {
        return !CustomDns.dohUrl().isEmpty();
    }

    @Override
    DnsLookup resolve(String host) {
        List<InetAddress> addresses = new ArrayList<>();
        DnsMessage.Ttl ttl = new DnsMessage.Ttl();
        boolean timedOut = false;
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        for (int type : new int[]{DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA}) {
            if (System.currentTimeMillis() > deadline) break;
            HttpURLConnection connection = null;
            boolean complete = false;
            try {
                byte[] query = DnsMessage.buildQuery(host, type);
                String server = CustomDns.dohUrl();
                String url = server + (server.contains("?") ? "&" : "?") + "dns="
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
                if (connection.getContentLength() > MAX_BYTES) throw new IOException("DoH answer is too big");
                try (InputStream input = connection.getInputStream()) {
                    addresses.addAll(DnsMessage.parseResponse(readAll(input, deadline), ttl));
                }
                complete = true;
            } catch (IOException ex) {
                // A timeout or a refused connection: the second query would wait for the same.
                Logger.printInfo(() -> "DoH lookup failed for " + host + ": " + ex);
                timedOut = ex instanceof SocketTimeoutException;
                break;
            } catch (Exception ex) {
                Logger.printInfo(() -> "DoH lookup failed for " + host + ": " + ex);
            } finally {
                // A fully read answer leaves the connection for the next query; anything else must not keep its socket
                // until the garbage collector.
                if (!complete && connection != null) connection.disconnect();
            }
        }
        if (addresses.isEmpty()) return DnsLookup.notFound(timedOut);
        return DnsLookup.found(addresses, ttl.seconds());
    }

    /**
     * Reads the answer of a server. A reply that is too big or too slow is cut: the per-read timeout does not
     * stop a server that sends a byte at a time.
     */
    private static byte[] readAll(InputStream input, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_READ_BYTES) throw new IOException("DoH answer is too big");
            if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("DoH answer is too slow");
        }
        return out.toByteArray();
    }
}
