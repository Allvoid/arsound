package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of Kugou's lyrics service the lyrics need: a search that finds the texts for a name and a length, and the
 * timed text of one of them, the endpoints its desktop player uses, without a key. Its texts are timed and many songs
 * LRCLIB does not have are there. Requests go through {@link LyricsHttp}. Used by {@link KugouFinder}.
 */
final class KugouClient {
    private static final String API = "https://lyrics.kugou.com/";

    private final LyricsHttp http;
    /** The key each text is fetched with, by the id the search gave it. */
    private final Map<Long, String> accessKeys = new HashMap<>();

    KugouClient(LyricsHttp http) {
        this.http = http;
    }

    /** Texts for "artist - title" and a length, the likeliest first, still without their words. */
    List<LyricsRecord> search(String query, long durationMs) throws IOException {
        StringBuilder url = new StringBuilder(API).append("search?ver=1&man=yes&client=pc&keyword=").append(encode(query));
        if (durationMs > 0) url.append("&duration=").append(durationMs);
        String body = http.get(url.toString());
        List<LyricsRecord> songs = new ArrayList<>();
        if (body == null) return songs;
        try {
            JSONArray candidates = new JSONObject(body).optJSONArray("candidates");
            for (int i = 0; candidates != null && i < candidates.length(); i++) {
                JSONObject item = candidates.optJSONObject(i);
                if (item == null) continue;
                long id;
                try {
                    id = Long.parseLong(item.optString("id", ""));
                } catch (NumberFormatException ex) {
                    continue;
                }
                String key = item.optString("accesskey", "");
                if (key.isEmpty()) continue;
                accessKeys.put(id, key);
                long length = item.optLong("duration", 0);
                songs.add(new LyricsRecord(id, item.optString("song", ""), item.optString("singer", ""),
                        length > 0 ? length / 1000.0 : Double.NaN, false, "", ""));
            }
        } catch (JSONException ex) {
            throw new IOException("Kugou sent a reply that is not a list of texts", ex);
        }
        return songs;
    }

    /** The song with its text: synced when Kugou has the timings, plain otherwise, or nothing when it has none. */
    LyricsRecord withText(LyricsRecord song) throws IOException {
        String key = accessKeys.get(song.id);
        if (key == null) return song;
        String body = http.get(API + "download?ver=1&client=pc&fmt=lrc&charset=utf8&id=" + song.id + "&accesskey=" + encode(key));
        if (body == null) return song;
        String text;
        try {
            String content = new JSONObject(body).optString("content", "");
            text = content.isEmpty() ? "" : new String(java.util.Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (JSONException | IllegalArgumentException ex) {
            throw new IOException("Kugou sent a reply that is not a text", ex);
        }
        // The same shape as NetEase's text: credit lines at the top, a stamp at every sung line.
        return NeteaseClient.withText(song, text);
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text == null ? "" : text, "UTF-8");
    }
}
