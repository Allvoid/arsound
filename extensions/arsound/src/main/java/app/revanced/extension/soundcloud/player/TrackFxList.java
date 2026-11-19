package app.revanced.extension.soundcloud.player;

import org.json.JSONArray;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tracks that keep a reverb or a bass boost of their own, as they are kept: track urn to the strength of each
 * ({@link VersionStrength#OFF} is a choice: "this one without reverb"; no strength at all is "as for every track"), the
 * one set longest ago first. At most {@link #MAX_TRACKS}; one more pushes the oldest out. Stored as one text, a JSON
 * array of triples [urn, reverb, bass] with an empty text for "as for every track", so the order is kept. No Android
 * here, so tests can run it. The same as the rows "urn=reverb,bass" of Arsound Desktop.
 */
final class TrackFxList {
    /** As the list of the speeds: far more than anyone sets by hand. */
    static final int MAX_TRACKS = 500;

    /** What one track keeps; a null strength follows the common choice. */
    static final class Own {
        static final Own NONE = new Own(null, null);
        final VersionStrength reverb;
        final VersionStrength bass;

        Own(VersionStrength reverb, VersionStrength bass) {
            this.reverb = reverb;
            this.bass = bass;
        }

        boolean isEmpty() {
            return reverb == null && bass == null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Own && ((Own) other).reverb == reverb && ((Own) other).bass == bass;
        }

        @Override
        public int hashCode() {
            return (reverb == null ? 0 : reverb.ordinal() + 1) * 31 + (bass == null ? 0 : bass.ordinal() + 1);
        }
    }

    private final LinkedHashMap<String, Own> rows = new LinkedHashMap<>();

    /** Reads the stored text; a text that cannot be read is an empty list. */
    static TrackFxList parse(String stored) {
        TrackFxList list = new TrackFxList();
        if (stored == null || stored.isEmpty()) return list;
        try {
            JSONArray triples = new JSONArray(stored);
            for (int i = 0; i < triples.length(); i++) {
                JSONArray triple = triples.optJSONArray(i);
                if (triple == null || triple.length() != 3) continue;
                String urn = triple.optString(0, "");
                Own own = new Own(known(triple.optString(1, "")), known(triple.optString(2, "")));
                if (!urn.isEmpty() && !own.isEmpty()) list.rows.put(urn, own);
            }
        } catch (Exception ex) {
            list.rows.clear();
        }
        list.trim();
        return list;
    }

    /** The strength with this key, or null for an empty or unknown key: {@link VersionStrength#of} takes it for "off". */
    private static VersionStrength known(String key) {
        for (VersionStrength strength : VersionStrength.values()) if (strength.key.equals(key)) return strength;
        return null;
    }

    /** What the track keeps of its own; {@link Own#NONE} if nothing. */
    Own get(String urn) {
        Own own = urn == null ? null : rows.get(urn);
        return own == null ? Own.NONE : own;
    }

    /** Sets the reverb and the bass of the track, as its newest entry; nothing for both takes the track's row away. */
    void put(String urn, VersionStrength reverb, VersionStrength bass) {
        if (urn == null || urn.isEmpty()) return;
        rows.remove(urn);
        Own own = new Own(reverb, bass);
        if (!own.isEmpty()) rows.put(urn, own);
        trim();
    }

    int size() {
        return rows.size();
    }

    private void trim() {
        Iterator<Map.Entry<String, Own>> oldest = rows.entrySet().iterator();
        while (rows.size() > MAX_TRACKS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** The text to store. */
    String format() {
        JSONArray triples = new JSONArray();
        for (Map.Entry<String, Own> row : rows.entrySet()) {
            Own own = row.getValue();
            triples.put(new JSONArray().put(row.getKey())
                    .put(own.reverb == null ? "" : own.reverb.key)
                    .put(own.bass == null ? "" : own.bass.key));
        }
        return triples.toString();
    }

    /** The strength a track plays with: its own, or the common choice where it has none. */
    static VersionStrength effective(VersionStrength own, VersionStrength common) {
        return own != null ? own : common;
    }
}
