package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of LRCLIB's open API the lyrics need: one exact lookup and two searches. No key; the service only asks
 * for an honest User-Agent and for requests to be kept few, which {@link LrclibHttp} sees to.
 */
final class LrclibClient {
    private static final String API = "https://lrclib.net/api/";

    /** Turns the body of a reply into what the caller wants. */
    private interface Parser<T> {
        T parse(String body) throws JSONException;
    }

    private final LrclibHttp http;

    LrclibClient(LrclibHttp http) {
        this.http = http;
    }

    /**
     * The record for this song with a duration within two seconds of the given one (LRCLIB's rule).
     *
     * @return Null if LRCLIB has no such record.
     */
    LrclibRecord get(String artist, String track, long durationMs) throws IOException {
        StringBuilder url = new StringBuilder(API).append("get?artist_name=").append(encode(artist))
                .append("&track_name=").append(encode(track));
        if (durationMs > 0) url.append("&duration=").append(Math.round(durationMs / 1000.0));
        String body = http.get(url.toString());
        if (body == null) return null;
        return parse(body, "LRCLIB sent a reply that is not a record", new Parser<LrclibRecord>() {
            @Override
            public LrclibRecord parse(String text) throws JSONException {
                return record(new JSONObject(text));
            }
        });
    }

    /** Up to twenty records, the likeliest first. Give a track (and an artist), or a free query. */
    List<LrclibRecord> search(String track, String artist, String query) throws IOException {
        StringBuilder url = new StringBuilder(API).append("search?");
        if (query != null) {
            url.append("q=").append(encode(query));
        } else {
            url.append("track_name=").append(encode(track));
            if (artist != null && !artist.isEmpty()) url.append("&artist_name=").append(encode(artist));
        }
        String body = http.get(url.toString());
        if (body == null) return new ArrayList<>();
        return parse(body, "LRCLIB sent a reply that is not a list of records", new Parser<List<LrclibRecord>>() {
            @Override
            public List<LrclibRecord> parse(String text) throws JSONException {
                List<LrclibRecord> records = new ArrayList<>();
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) records.add(record(item));
                }
                return records;
            }
        });
    }

    /** A reply that is not the JSON it should be is a failed lookup, like a lost connection. */
    private static <T> T parse(String body, String complaint, Parser<T> parser) throws IOException {
        try {
            return parser.parse(body);
        } catch (JSONException ex) {
            throw new IOException(complaint, ex);
        }
    }

    private static LrclibRecord record(JSONObject item) throws JSONException {
        return new LrclibRecord(item.getLong("id"), string(item, "trackName"), string(item, "artistName"),
                item.isNull("duration") ? Double.NaN : item.optDouble("duration", Double.NaN),
                item.optBoolean("instrumental", false), string(item, "plainLyrics"), string(item, "syncedLyrics"));
    }

    /** A text field, empty for a missing one or a JSON null (Android's optString turns that into "null"). */
    private static String string(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key, "");
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text == null ? "" : text, "UTF-8");
    }
}
