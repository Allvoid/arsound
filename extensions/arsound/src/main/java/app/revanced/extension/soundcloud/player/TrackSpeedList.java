package app.revanced.extension.soundcloud.player;

import org.json.JSONArray;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tracks that have a speed of their own, as they are kept: track urn to the key of a {@link VersionSpeed}, the one
 * set longest ago first. At most {@link #MAX_TRACKS}; one more pushes the oldest out. Stored as one text, a JSON array
 * of pairs, so the order is kept. No Android here, so tests can run it.
 */
final class TrackSpeedList {
    /** Far more than anyone sets by hand, and still a short text in the settings (and in their backup). */
    static final int MAX_TRACKS = 500;

    private final LinkedHashMap<String, String> speeds = new LinkedHashMap<>();

    /** Reads the stored text; a text that cannot be read is an empty list. */
    static TrackSpeedList parse(String stored) {
        TrackSpeedList list = new TrackSpeedList();
        if (stored == null || stored.isEmpty()) return list;
        try {
            JSONArray pairs = new JSONArray(stored);
            for (int i = 0; i < pairs.length(); i++) {
                JSONArray pair = pairs.optJSONArray(i);
                if (pair == null || pair.length() != 2) continue;
                String urn = pair.optString(0, "");
                VersionSpeed speed = known(pair.optString(1, ""));
                if (!urn.isEmpty() && speed != null) list.speeds.put(urn, speed.key);
            }
        } catch (Exception ex) {
            list.speeds.clear();
        }
        list.trim();
        return list;
    }

    /** The speed with this key, or null: {@link VersionSpeed#of} takes an unknown key for "normal". */
    private static VersionSpeed known(String key) {
        for (VersionSpeed speed : VersionSpeed.values()) if (speed.key.equals(key)) return speed;
        return null;
    }

    /** The speed the track always plays at, or null if it has none of its own. */
    VersionSpeed get(String urn) {
        String key = urn == null ? null : speeds.get(urn);
        return key == null ? null : known(key);
    }

    /** Sets the speed of the track, as its newest entry; null takes its own speed away. */
    void put(String urn, VersionSpeed speed) {
        if (urn == null || urn.isEmpty()) return;
        speeds.remove(urn);
        if (speed != null) speeds.put(urn, speed.key);
        trim();
    }

    int size() {
        return speeds.size();
    }

    private void trim() {
        Iterator<Map.Entry<String, String>> oldest = speeds.entrySet().iterator();
        while (speeds.size() > MAX_TRACKS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** The text to store. */
    String format() {
        JSONArray pairs = new JSONArray();
        for (Map.Entry<String, String> speed : speeds.entrySet()) {
            pairs.put(new JSONArray().put(speed.getKey()).put(speed.getValue()));
        }
        return pairs.toString();
    }
}
