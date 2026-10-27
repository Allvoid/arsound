package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of LRCLIB's open API the lyrics need: one exact lookup and two searches. No key; the service only asks
 * for an honest User-Agent and for requests to be kept few, so every request waits for a gap after the last one.
 */
final class LrclibClient {
    /** A record of the LRCLIB database: one version of one song, with the text it was uploaded with. */
    static final class Record {
        final long id;
        final String trackName;
        final String artistName;
        /** Seconds, or NaN when the record has none. */
        final double durationSeconds;
        final boolean instrumental;
        final String plain;
        final String synced;

        Record(long id, String trackName, String artistName, double durationSeconds, boolean instrumental,
               String plain, String synced) {
            this.id = id;
            this.trackName = trackName;
            this.artistName = artistName;
            this.durationSeconds = durationSeconds;
            this.instrumental = instrumental;
            this.plain = plain;
            this.synced = synced;
        }

        boolean hasText() {
            return !isEmpty(plain) || !isEmpty(synced);
        }
    }

    /** Called before every request; throws to stop it (the region guard). */
    interface Gate {
        void check() throws IOException;
    }

    private static final String API = "https://lrclib.net/api/";
    private static final String USER_AGENT = "Arsound (https://github.com/Allvoid/arsound)";
    private static final long REQUEST_GAP_MS = 300;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_WAIT_MS = 3_000;

    private static final Object PACE = new Object();
    private static long lastRequest;

    private final Gate gate;

    LrclibClient(Gate gate) {
        this.gate = gate;
    }

    /**
     * The record for this song with a duration within two seconds of the given one (LRCLIB's rule).
     *
     * @return Null if LRCLIB has no such record.
     */
    Record get(String artist, String track, long durationMs) throws IOException {
        StringBuilder url = new StringBuilder(API).append("get?artist_name=").append(encode(artist))
                .append("&track_name=").append(encode(track));
        if (durationMs > 0) url.append("&duration=").append(Math.round(durationMs / 1000.0));
        String body = fetch(url.toString());
        if (body == null) return null;
        try {
            return record(new JSONObject(body));
        } catch (JSONException ex) {
            throw new IOException("LRCLIB sent a reply that is not a record", ex);
        }
    }

    /** Up to twenty records, the likeliest first. Give a track (and an artist), or a free query. */
    List<Record> search(String track, String artist, String query) throws IOException {
        StringBuilder url = new StringBuilder(API).append("search?");
        if (query != null) {
            url.append("q=").append(encode(query));
        } else {
            url.append("track_name=").append(encode(track));
            if (artist != null && !artist.isEmpty()) url.append("&artist_name=").append(encode(artist));
        }
        String body = fetch(url.toString());
        List<Record> records = new ArrayList<>();
        if (body == null) return records;
        try {
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) records.add(record(item));
            }
        } catch (JSONException ex) {
            throw new IOException("LRCLIB sent a reply that is not a list of records", ex);
        }
        return records;
    }

    private static Record record(JSONObject item) throws JSONException {
        return new Record(item.getLong("id"), string(item, "trackName"), string(item, "artistName"),
                item.isNull("duration") ? Double.NaN : item.optDouble("duration", Double.NaN),
                item.optBoolean("instrumental", false), string(item, "plainLyrics"), string(item, "syncedLyrics"));
    }

    /** A text field, empty for a missing one or a JSON null (Android's optString turns that into "null"). */
    private static String string(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key, "");
    }

    private static boolean isEmpty(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text == null ? "" : text, "UTF-8");
    }

    /**
     * @return The body of a 200 reply, or null for 404, which is LRCLIB's answer for "no such record". A busy
     * server (429, 5xx) and a lost connection are tried again a little later and then thrown, never taken for 404.
     */
    private String fetch(String url) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) pause(failure instanceof Busy ? ((Busy) failure).waitMs : 600);
            gate.check();
            pace();
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
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
                // A dropped connection or a failed handshake: once more, then give up.
                if (attempt >= 1) throw ex;
                failure = ex;
            } finally {
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

    private static void pace() throws IOException {
        long wait;
        synchronized (PACE) {
            wait = lastRequest + REQUEST_GAP_MS - System.currentTimeMillis();
            lastRequest = System.currentTimeMillis() + Math.max(0, wait);
        }
        if (wait > 0) pause(wait);
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", ex);
        }
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
