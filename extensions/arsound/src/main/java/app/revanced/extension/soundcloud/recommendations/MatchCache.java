package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The songs already looked up on SoundCloud, kept for two weeks so a refresh does not search for them again. */
final class MatchCache {
    private static final long KEPT_MS = TimeUnit.DAYS.toMillis(14);

    /** A song found on SoundCloud, or the answer "not found" (an empty urn). */
    static final class Hit {
        final String urn;
        final String title;
        final long durationMs;

        Hit(String urn, String title, long durationMs) {
            this.urn = urn;
            this.title = title;
            this.durationMs = durationMs;
        }

        static Hit notFound() {
            return new Hit("", "", 0);
        }
    }

    private final JSONObject cache;
    private final long now = System.currentTimeMillis();

    private MatchCache(JSONObject cache) {
        this.cache = cache;
    }

    /** Reads the cache from the phone; a damaged one is an empty one. */
    static MatchCache load() {
        try {
            return new MatchCache(new JSONObject(ForYouPrefs.matches()));
        } catch (Exception ex) {
            return new MatchCache(new JSONObject());
        }
    }

    /** The kept match of a song key, or null if there is none or it is old. */
    Hit fresh(String key) {
        JSONObject cached = cache.optJSONObject(key);
        // Matches kept by older versions have no title or length: they are looked up again.
        if (cached == null || !cached.has("title") || now - cached.optLong("time") >= KEPT_MS) return null;
        return new Hit(cached.optString("urn"), cached.optString("title"), cached.optLong("dur"));
    }

    void put(String key, Hit hit) throws Exception {
        // A match read from the cache keeps its first date, so it expires after two weeks, however often it is used.
        JSONObject earlier = cache.optJSONObject(key);
        long since = earlier != null && hit.urn.equals(earlier.optString("urn"))
                && now - earlier.optLong("time") < KEPT_MS ? earlier.optLong("time") : now;
        cache.put(key, new JSONObject().put("urn", hit.urn).put("time", since)
                .put("title", hit.title).put("dur", hit.durationMs));
    }

    /** Stores the cache and the keys of the new playlist. Old matches are dropped so the store does not grow for ever. */
    void save(Set<String> chosenKeys) throws Exception {
        JSONObject kept = new JSONObject();
        Iterator<String> keys = cache.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject value = cache.optJSONObject(key);
            if (value != null && now - value.optLong("time") < KEPT_MS) kept.put(key, value);
        }
        ForYouPrefs.storeMatches(kept.toString(), chosenKeys);
    }
}
