package app.revanced.extension.soundcloud.scrobble;

import org.json.JSONException;
import org.json.JSONObject;

/** One listen: what was played and when it started. Immutable. */
final class ScrobbleListen {
    final String artist;
    final String title;
    /** Unix seconds of the start; 0 for "playing now". */
    final long listenedAt;
    final long durationMs;

    ScrobbleListen(String artist, String title, long listenedAt, long durationMs) {
        this.artist = artist;
        this.title = title;
        this.listenedAt = listenedAt;
        this.durationMs = durationMs;
    }

    /** The form kept in the queue file: short keys, as on the desktop. */
    JSONObject toJson() {
        try {
            return new JSONObject().put("a", artist).put("t", title).put("at", listenedAt).put("d", durationMs);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** @return The listen, or null when the entry is damaged or lacks an artist, a title or a start time. */
    static ScrobbleListen fromJson(JSONObject object) {
        if (object == null) return null;
        String artist = object.optString("a", "");
        String title = object.optString("t", "");
        long at = object.optLong("at", 0);
        if (artist.isEmpty() || title.isEmpty() || at <= 0) return null;
        return new ScrobbleListen(artist, title, at, Math.max(0, object.optLong("d", 0)));
    }

    boolean same(ScrobbleListen other) {
        return other != null && listenedAt == other.listenedAt && title.equals(other.title);
    }
}
