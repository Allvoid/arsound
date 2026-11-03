package app.revanced.extension.soundcloud.wallpaper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * The network calls the wallpaper sources share: one connection set-up (the name the services see, the timeouts), the
 * reading of a picture or an answer with a size limit, the download into a file, and the rule that decides where a
 * redirect may lead.
 * <p>
 * Redirects are not followed by the system: every one is checked against the hosts of the source
 * ({@link WallpaperSource#ownsHost}), so an address a service hands out can never lead the app to fetch from anywhere
 * else. The region guard is asked before every call, and a source that limits its parallel reads
 * ({@link WallpaperSource#imageGate}) is waited for.
 */
final class WallpaperHttp {
    private static final int MAX_REDIRECTS = 4;
    private static final long GATE_WAIT_SECONDS = 60;
    private static final int MAX_DOWNLOAD_BYTES = 80 * 1024 * 1024;

    private WallpaperHttp() {
    }

    /** Told how much of a picture is downloaded; the total is -1 when the server does not say. */
    interface Progress {
        void onProgress(long done, long total);
    }

    /** Decides where a redirect may lead. */
    interface Hosts {
        boolean allows(String address);
    }

    /**
     * The name the services see. Wikimedia asks for a name that says who calls and where to read about it; the same
     * form goes to Wallhaven.
     */
    static String userAgent() {
        return "Arsound/" + UpdateChecker.VERSION + " (https://github.com/Allvoid/arsound)";
    }

    /** A connection to an address with the timeouts and the name the services see; redirects are left to the caller. */
    static HttpURLConnection connect(String address, int connectMs, int readMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", userAgent());
        return connection;
    }

    /**
     * Opens an address and follows its redirects, at most four, each only to an address {@code hosts} allows.
     *
     * @return A connection whose answer is not a redirect; the caller reads it and disconnects.
     */
    static HttpURLConnection openFollowing(String address, int connectMs, int readMs, Hosts hosts) throws IOException {
        return openFollowing(address, connectMs, readMs, hosts, null, null);
    }

    /**
     * The same, with one header on every call: for a key that must go to the service's own hosts only, and so is sent
     * only to addresses {@code hosts} allows (a redirect elsewhere is refused before it is followed).
     */
    static HttpURLConnection openFollowing(String address, int connectMs, int readMs, Hosts hosts, String header, String value)
            throws IOException {
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = connect(current, connectMs, readMs);
            if (header != null && value != null) connection.setRequestProperty(header, value);
            boolean handedOver = false;
            try {
                int code = connection.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    handedOver = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("A redirect with no address");
                String next = new URL(new URL(current), location).toString();
                if (!hosts.allows(next)) throw new IOException("A redirect to another host");
                current = next;
            } finally {
                if (!handedOver) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** True when the address is on the same host as the other one, over https. */
    static boolean sameHost(String address, String other) {
        try {
            URL a = new URL(address), b = new URL(other);
            return "https".equals(b.getProtocol()) && a.getHost().toLowerCase(Locale.ROOT).equals(b.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            return false;
        }
    }

    /** The seconds an answer 429 asks to wait, or 0. */
    static int retryAfter(HttpURLConnection connection) {
        try {
            return Math.max(0, Integer.parseInt(connection.getHeaderField("Retry-After").trim()));
        } catch (Exception ex) {
            return 0;
        }
    }

    // region Reading

    /**
     * Reads the answer of a service's API as text, from the address and from redirects on the same host.
     *
     * @param service The name for the failure that is thrown.
     * @throws WallpaperSource.Failure For an answer that is not 200.
     */
    static String readText(String service, String address, int limit) throws IOException {
        HttpURLConnection connection = openFollowing(address, 10_000, 20_000, next -> sameHost(address, next));
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new WallpaperSource.Failure(service, code, code == 429 ? retryAfter(connection) : 0);
            try (InputStream input = connection.getInputStream()) {
                return new String(readAll(input, limit), "UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Reads a picture of a source into memory, up to {@code limit} bytes: a bigger one is refused, from its announced
     * size or when it grows over the limit. Blocks.
     */
    static byte[] readPicture(WallpaperSource source, String address, int limit) throws IOException {
        if (!source.ownsHost(address)) throw new IOException("Not an address of " + source.name());
        RegionGuard.throwIfBlockedAnyHost();
        Semaphore gate = source.imageGate();
        boolean held = acquire(gate);
        try {
            HttpURLConnection connection = openFollowing(address, 10_000, 15_000, source::ownsHost);
            try {
                if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
                if (connection.getContentLength() > limit) throw new IOException("The picture is too big");
                try (InputStream input = connection.getInputStream()) {
                    return readAll(input, limit);
                }
            } finally {
                connection.disconnect();
            }
        } finally {
            if (held) gate.release();
        }
    }

    /**
     * Downloads a picture of a source into a file. Blocks: call it off the main thread.
     *
     * @param cancelled Set by the screen when the user gives up; the download then stops and the file is removed.
     */
    static void download(WallpaperSource source, String address, File target, Progress progress, AtomicBoolean cancelled)
            throws IOException {
        if (!source.ownsHost(address)) throw new IOException("Not an address of " + source.name());
        RegionGuard.throwIfBlockedAnyHost();
        Semaphore gate = source.imageGate();
        boolean held = acquire(gate);
        boolean done = false;
        try {
            if (cancelled.get()) throw new IOException("Cancelled");
            HttpURLConnection connection = openFollowing(address, 15_000, 30_000, source::ownsHost);
            try {
                if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
                long total = connection.getContentLength();
                if (total > MAX_DOWNLOAD_BYTES) throw new IOException("The picture is too big");
                byte[] buffer = new byte[32 * 1024];
                long received = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) > 0) {
                        if (cancelled.get()) throw new IOException("Cancelled");
                        received += count;
                        if (received > MAX_DOWNLOAD_BYTES) throw new IOException("The picture is too big");
                        output.write(buffer, 0, count);
                        if (progress != null) progress.onProgress(received, total);
                    }
                }
                done = true;
            } finally {
                connection.disconnect();
            }
        } finally {
            if (held) gate.release();
            if (!done) target.delete();
        }
    }

    /** Waits for a permit of the source's gate, if it has one. Returns true when a permit is held and must be released. */
    private static boolean acquire(Semaphore gate) throws IOException {
        if (gate == null) return false;
        try {
            if (!gate.tryAcquire(GATE_WAIT_SECONDS, TimeUnit.SECONDS)) throw new IOException("Too many pictures are being read");
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) {
            output.write(buffer, 0, count);
            if (output.size() > limit) throw new IOException("The answer is too big");
        }
        return output.toByteArray();
    }

    // endregion
}
