package app.revanced.extension.soundcloud.download;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * The requests that fetch the files of a download: the segments of an HLS track ({@link HlsDownloader}) and the file of
 * a progressive one ({@link ProgressiveDownloader}). A request that failed for a passing reason is sent again.
 */
final class DownloadHttp {
    /** How many times a request that failed for a passing reason is sent again, after a pause of 1, 2 and 4 seconds. */
    static final int MAX_RETRIES = 3;
    /** The longest pause before a new try, also for a longer Retry-After. */
    private static final long MAX_RETRY_PAUSE_MS = 15_000;

    private DownloadHttp() {
    }

    /** A reply other than 200, with the pause the server asked for. */
    static final class HttpStatusException extends IOException {
        final int code;
        /** The Retry-After of the reply in milliseconds, or 0 if it gave no number of seconds. */
        final long retryAfterMs;

        HttpStatusException(int code, String url, long retryAfterMs) {
            super("HTTP " + code + " for " + url);
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    interface NetworkCall<T> {
        T run() throws Exception;
    }

    /**
     * Runs one request again after a failure that may pass by itself: a timeout, a dropped connection or a busy
     * server. A single request of a track of hundreds of segments must not throw all the others away.
     */
    static <T> T withRetry(NetworkCall<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.run();
            } catch (IOException ex) {
                // pool.shutdownNow() interrupts the threads of an assembly that has already failed.
                if (attempt >= MAX_RETRIES || !isTransient(ex) || Thread.currentThread().isInterrupted()) throw ex;
                long pause = pauseBeforeRetry(ex, attempt);
                int retry = attempt + 1;
                Logger.printInfo(() -> "HLS request failed (" + ex + "), retry " + retry + " of " + MAX_RETRIES
                        + " in " + pause + " ms");
                Thread.sleep(pause);
            }
        }
    }

    /** How long to wait before trying again: what the server asked for, else a pause that doubles. */
    static long pauseBeforeRetry(IOException ex, int attempt) {
        return ex instanceof HttpStatusException && ((HttpStatusException) ex).retryAfterMs > 0
                ? ((HttpStatusException) ex).retryAfterMs
                : Math.min(1000L << attempt, MAX_RETRY_PAUSE_MS);
    }

    /** Whether asking again can help. A refusal (403, 404), the region guard and a broken link would answer the same. */
    static boolean isTransient(IOException ex) {
        if (ex instanceof RegionGuard.BlockedException || ex instanceof MalformedURLException) return false;
        if (ex instanceof HttpStatusException) {
            int code = ((HttpStatusException) ex).code;
            return code == 408 || code == 429 || code >= 500;
        }
        return true;
    }

    static byte[] readBytes(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    static String readText(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    static HttpURLConnection open(String url) throws Exception {
        return open(url, 0, null);
    }

    /**
     * @param from      The first byte to ask for, 0 for the whole file. A server that ignores the request answers with the
     *                  whole file all the same (200), which the caller can tell from a part (206).
     * @param userAgent Sent as the User-Agent, or null to leave the system's own.
     */
    static HttpURLConnection open(String url, long from, String userAgent) throws Exception {
        RegionGuard.throwIfBlocked(new URL(url).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        boolean opened = false;
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
            if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
            int code = connection.getResponseCode();
            boolean part = from > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            if (code != HttpURLConnection.HTTP_OK && !part) throw new HttpStatusException(code, url, retryAfterMs(connection));
            opened = true;
            return connection;
        } finally {
            // A reply that failed to arrive must not leave its socket open, now that it is asked for again.
            if (!opened) connection.disconnect();
        }
    }

    /** The pause the server asked for in Retry-After, at most {@link #MAX_RETRY_PAUSE_MS}; 0 if it gave none. */
    private static long retryAfterMs(HttpURLConnection connection) {
        try {
            String header = connection.getHeaderField("Retry-After");
            if (header != null) {
                long seconds = Long.parseLong(header.trim());
                // Capped before the multiplication, so a huge number cannot overflow into a negative pause.
                if (seconds > 0) return Math.min(seconds, MAX_RETRY_PAUSE_MS / 1000) * 1000;
            }
        } catch (NumberFormatException ignored) {
            // A date instead of seconds: the growing pause is good enough.
        }
        return 0;
    }
}
