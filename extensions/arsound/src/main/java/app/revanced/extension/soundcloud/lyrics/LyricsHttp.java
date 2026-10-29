package app.revanced.extension.soundcloud.lyrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The way to the lyrics services (LRCLIB, NetEase): one GET at a time, kept to a gap after the last request, tried
 * again when the server is busy or the connection drops, and cut short when the lookup is not wanted any more. Knows
 * nothing of what the replies say.
 */
final class LyricsHttp {
    /** Called before every request; throws to stop it (the region guard). */
    interface Gate {
        void check() throws IOException;
    }

    /**
     * The caller's hold on a lookup in flight: whether it is still wanted, and the connection in use, so that a wait on
     * the network can be cut at once instead of running out its timeouts.
     */
    interface Watch extends LookupCancel {
        /** The connection about to be used; null once it is done with. */
        void connecting(HttpURLConnection connection);
    }

    private static final String USER_AGENT = "Arsound (https://github.com/Allvoid/arsound)";
    private static final long REQUEST_GAP_MS = 300;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_WAIT_MS = 3_000;

    private static final Object PACE = new Object();
    private static long lastRequest;

    private final Gate gate;
    /** Null when nobody can cancel. */
    private final Watch watch;

    LyricsHttp(Gate gate, Watch watch) {
        this.gate = gate;
        this.watch = watch;
    }

    /**
     * @return The body of a 200 reply, or null for 404, which is LRCLIB's answer for "no such record". A busy
     * server (429, 5xx) and a lost connection are tried again a little later and then thrown, never taken for 404.
     */
    String get(String url) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            // A track that is not wanted any more must not hold the one worker for the next track.
            stopIfCancelled();
            if (attempt > 0) Pause.cancellable(failure instanceof Busy ? ((Busy) failure).waitMs : 600, watch);
            gate.check();
            pace();
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                if (watch != null) watch.connecting(connection);
                stopIfCancelled();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code == 404) {
                    drain(connection.getErrorStream());
                    return null;
                }
                if (code == 200) return read(connection.getInputStream());
                drain(connection.getErrorStream());
                if (code == 429 || code / 100 == 5) {
                    failure = new Busy("LRCLIB is busy, HTTP " + code, retryAfter(connection));
                    continue;
                }
                throw new Rejected("LRCLIB answered HTTP " + code);
            } catch (Rejected ex) {
                throw ex;
            } catch (IOException ex) {
                // A dropped connection or a failed handshake: once more, then give up. A connection cut because the
                // track is not wanted any more is not tried again.
                if (attempt >= 1 || isCancelled()) throw ex;
                failure = ex;
            } finally {
                if (watch != null) watch.connecting(null);
                connection.disconnect();
            }
        }
        throw failure != null ? failure : new IOException("LRCLIB did not answer");
    }

    /** A reply that says no; asking again does not help. */
    private static final class Rejected extends IOException {
        Rejected(String message) {
            super(message);
        }
    }

    /** A reply that asks to come back later. */
    private static final class Busy extends IOException {
        final long waitMs;

        Busy(String message, long waitMs) {
            super(message);
            this.waitMs = waitMs;
        }
    }

    private static long retryAfter(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        long seconds = 1;
        if (header != null) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                // A date instead of seconds: the default wait is good enough.
            }
        }
        return Math.max(300, Math.min(seconds * 1000, MAX_RETRY_WAIT_MS));
    }

    private boolean isCancelled() {
        return watch != null && watch.isCancelled();
    }

    private void stopIfCancelled() throws IOException {
        if (isCancelled()) throw new IOException("Lookup cancelled");
    }

    /** Waits for the gap after the last request of any lookup; the next one's turn is booked before the wait. */
    private void pace() throws IOException {
        long wait;
        synchronized (PACE) {
            wait = lastRequest + REQUEST_GAP_MS - System.currentTimeMillis();
            lastRequest = System.currentTimeMillis() + Math.max(0, wait);
        }
        if (wait > 0) Pause.cancellable(wait, watch);
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void drain(InputStream stream) {
        if (stream == null) return;
        try {
            read(stream);
        } catch (IOException ignored) {
            // The reply is not wanted.
        }
    }
}
