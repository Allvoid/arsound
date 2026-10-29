package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The part of NetEase Cloud Music's web API the lyrics need: a search for songs and the timed text of one song, the
 * same endpoints its web player uses, without a key. It holds many songs LRCLIB has too, popular Russian ones among
 * them, and is asked when LRCLIB has nothing or cannot be reached. Requests go through {@link LyricsHttp} like
 * LRCLIB's. Used by {@link NeteaseFinder}.
 */
final class NeteaseClient {
    private static final String API = "https://music.163.com/api/";
    /** Songs a search returns. */
    private static final int SEARCH_LIMIT = 10;
    /**
     * Lines that name the authors, not words to sing: "作词 : …" (words by), "作曲 : …" (music by) and the like, which
     * NetEase puts at the top of a text.
     */
    private static final Pattern CREDIT = Pattern.compile(
            "(?m)^(\\[[^\\]]*])*\\s*(作词|作曲|编曲|制作人|制作|和声|混音|母带|监制|出品|吉他|贝斯|鼓|录音|"
                    + "lyrics by|composed by|producer|written by)\\s*[:：].*$\\n?",
            Pattern.CASE_INSENSITIVE);
    /** A line that starts with a time stamp: the text is synced. */
    private static final Pattern STAMPED = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}");
    /** NetEase's text for a song without words: "pure music, please enjoy". */
    private static final String INSTRUMENTAL = "纯音乐，请欣赏";

    private final LyricsHttp http;

    NeteaseClient(LyricsHttp http) {
        this.http = http;
    }

    /** Songs for a free query, the likeliest first, still without their texts. */
    List<LyricsRecord> search(String query) throws IOException {
        String body = http.get(API + "search/get?type=1&limit=" + SEARCH_LIMIT + "&s=" + encode(query));
        if (body == null) return new ArrayList<>();
        try {
            return songs(answer(body).optJSONObject("result"));
        } catch (JSONException ex) {
            throw new IOException("NetEase sent a reply that is not a list of songs", ex);
        }
    }

    /** The song with its text: synced when NetEase has the timings, plain otherwise, or nothing when it has none. */
    LyricsRecord withText(LyricsRecord song) throws IOException {
        String body = http.get(API + "song/lyric?lv=1&id=" + song.id);
        if (body == null) return song;
        String text;
        try {
            JSONObject lrc = answer(body).optJSONObject("lrc");
            text = lrc == null || lrc.isNull("lyric") ? "" : lrc.optString("lyric", "");
        } catch (JSONException ex) {
            throw new IOException("NetEase sent a reply that is not a text", ex);
        }
        return withText(song, text);
    }

    /** The record of a song for the text NetEase has for it. */
    static LyricsRecord withText(LyricsRecord song, String text) {
        String words = CREDIT.matcher(text).replaceAll("").trim();
        boolean instrumental = words.contains(INSTRUMENTAL);
        if (instrumental || words.isEmpty()) {
            return new LyricsRecord(song.id, song.trackName, song.artistName, song.durationSeconds, instrumental, "", "");
        }
        boolean timed = STAMPED.matcher(words).find();
        return new LyricsRecord(song.id, song.trackName, song.artistName, song.durationSeconds, false,
                timed ? "" : words, timed ? words : "");
    }

    /** The songs of a search result; an empty list for none. */
    static List<LyricsRecord> songs(JSONObject result) throws JSONException {
        List<LyricsRecord> songs = new ArrayList<>();
        JSONArray array = result == null ? null : result.optJSONArray("songs");
        if (array == null) return songs;
        for (int i = 0; i < array.length(); i++) {
            JSONObject song = array.optJSONObject(i);
            if (song == null) continue;
            StringBuilder artists = new StringBuilder();
            JSONArray names = song.optJSONArray("artists");
            for (int j = 0; names != null && j < names.length(); j++) {
                JSONObject artist = names.optJSONObject(j);
                if (artist == null || artist.optString("name", "").isEmpty()) continue;
                if (artists.length() > 0) artists.append(", ");
                artists.append(artist.optString("name"));
            }
            long durationMs = song.optLong("duration", 0);
            songs.add(new LyricsRecord(song.getLong("id"), song.optString("name", ""), artists.toString(),
                    durationMs > 0 ? durationMs / 1000.0 : Double.NaN, false, "", ""));
        }
        return songs;
    }

    /** The reply, when NetEase says it is one: a refusal ("code" other than 200) is a failed lookup. */
    private static JSONObject answer(String body) throws IOException, JSONException {
        JSONObject json = new JSONObject(body);
        int code = json.optInt("code", 200);
        if (code != 200) throw new IOException("NetEase answered with code " + code);
        return json;
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text == null ? "" : text, "UTF-8");
    }
}
