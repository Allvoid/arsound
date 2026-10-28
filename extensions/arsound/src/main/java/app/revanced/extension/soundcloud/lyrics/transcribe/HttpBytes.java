package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Plain GET requests for the audio of a streamed track: the playlist, the key, the pieces, or the whole file.
 * Every request first asks {@link RegionGuard}, like the app's other requests to SoundCloud's servers.
 * Used by {@link HlsFetch} and {@link TrackAudio}.
 */
final class HttpBytes {
    /** Fetches the bytes behind an address; swapped for a fake in the tests of {@link HlsFetch}. */
    interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private HttpBytes() {
    }

    /** The fetcher the app uses: a small answer into memory, at most 8 MB (a piece of a track is a few hundred kilobytes). */
    static Fetcher small(final ResumableDownload.Cancel cancel) {
        return new Fetcher() {
            @Override
            public byte[] get(String url) throws IOException {
                HttpURLConnection connection = open(url);
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) > 0) {
                        if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                        output.write(buffer, 0, count);
                        if (output.size() > 8 * 1024 * 1024) throw new IOException("The answer is too big");
                    }
                    return output.toByteArray();
                } finally {
                    connection.disconnect();
                }
            }
        };
    }

    /** Saves the body of an answer to a file, refusing more than {@code maxBytes}. */
    static void copyTo(String url, File target, long maxBytes, ResumableDownload.Cancel cancel) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) > 0) {
                if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                total += count;
                if (total > maxBytes) throw new IOException("The file is too big");
                output.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("Not an HTTPS address");
        RegionGuard.throwIfBlocked(url.getHost());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection;
    }
}
