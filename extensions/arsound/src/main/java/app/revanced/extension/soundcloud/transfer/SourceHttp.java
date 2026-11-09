package app.revanced.extension.soundcloud.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.revanced.extension.soundcloud.network.StreamText;

/** The POST requests of the VK and YouTube Music sources: tried three times, "Stop" honoured between the tries. */
final class SourceHttp {
    /** The service answered with an error status that a new try does not change. */
    static final class StatusException extends IOException {
        final int code;

        StatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    private SourceHttp() {
    }

    /** The body of a 200 answer. */
    static String post(String url, Map<String, String> headers, String contentType, String body) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer stopped");
            if (attempt > 0) pause(attempt * 1_500L);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(20_000);
                connection.setRequestMethod("POST");
                // A redirect would carry the session's cookies and headers to wherever it leads.
                connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                if (code == 200) {
                    try (InputStream in = connection.getInputStream()) {
                        return StreamText.readUtf8(in);
                    }
                }
                drain(connection);
                if (code >= 300 && code < 400) throw new StatusException(code, url);
                if (code >= 400 && code < 500 && code != 408 && code != 429) throw new StatusException(code, url);
                failure = new IOException("HTTP " + code + " for " + url);
            } catch (StatusException ex) {
                throw ex;
            } catch (IOException ex) {
                if (ex instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) throw ex;
                failure = ex;
            } finally {
                connection.disconnect();
            }
        }
        throw failure;
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream error = connection.getErrorStream()) {
            if (error != null) while (error.read() != -1) {
                // Nothing to keep.
            }
        } catch (IOException ignored) {
        }
    }

    static void pause(long ms) throws InterruptedIOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Stopped");
        }
    }
}
