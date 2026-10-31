package app.revanced.extension.soundcloud.lyrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/**
 * The last place to look: lyrics.ovh, which keeps plain texts (no timings) and answers to an exact artist and title.
 * It has no length to check a text against, so only the first guess of the track is asked, and the words come without
 * timings. Credited to lyrics.ovh.
 */
final class OvhFinder {
    private static final String API = "https://api.lyrics.ovh/v1/";
    /** The line some of its texts begin with ("Paroles de la chanson ... par ..."). */
    private static final Pattern HEADING = Pattern.compile("^\\s*Paroles de la chanson[^\\n]*\\n+", Pattern.CASE_INSENSITIVE);

    private final LyricsHttp http;
    private final LookupCancel cancel;

    OvhFinder(LyricsHttp http, LookupCancel cancel) {
        this.http = http;
        this.cancel = cancel;
    }

    /** @return Found or missing. A failed request throws: it says nothing about the track. */
    LyricsResult find(TrackQuery query) throws IOException {
        if (query.durationMs > LyricsFinder.MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();
        TrackQuery.Variant first = query.variants.get(0);
        // A track without an artist cannot be asked for here.
        if (first.artist.isEmpty() || first.track.isEmpty()) return LyricsResult.missing();
        if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
        String body = http.get(API + encode(first.artist) + "/" + encode(first.track));
        if (body == null) return LyricsResult.missing();
        String text;
        try {
            text = new JSONObject(body).optString("lyrics", "");
        } catch (JSONException ex) {
            throw new IOException("lyrics.ovh sent a reply that is not a text", ex);
        }
        Lyrics words = LrcParser.parsePlain(HEADING.matcher(text.replace("\r\n", "\n")).replaceFirst(""));
        return words == null ? LyricsResult.missing() : LyricsResult.found(words.withSource(Lyrics.Source.OVH));
    }

    /** One segment of a path: a space is "%20" there, not "+". */
    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    }
}
