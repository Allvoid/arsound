package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The title and length of tracks seen in filtered lists, by urn, so a later list can be compared without asking. */
final class KnownTracks {
    private static final int MAX_TRACKS = 2000;

    private static final Map<String, SongRef> tracks = new LinkedHashMap<String, SongRef>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SongRef> eldest) {
            return size() > MAX_TRACKS;
        }
    };

    private KnownTracks() {
    }

    /** @param urn The key as it was given, not normalized. */
    static void remember(String urn, SongRef song) {
        synchronized (tracks) {
            tracks.put(urn, song);
        }
    }

    static SongRef get(String urn) {
        synchronized (tracks) {
            return tracks.get(urn);
        }
    }

    /** The urns of the "soundcloud:tracks:" kind that are not known yet, as bare ids. */
    static List<String> unknownIds(List<Object> urns) {
        List<String> missing = new ArrayList<>();
        synchronized (tracks) {
            for (Object urn : urns) {
                String value = String.valueOf(urn);
                if (!tracks.containsKey(value) && value.startsWith(TrackUrn.TRACKS)) {
                    missing.add(value.substring(TrackUrn.TRACKS.length()));
                }
            }
        }
        return missing;
    }
}
