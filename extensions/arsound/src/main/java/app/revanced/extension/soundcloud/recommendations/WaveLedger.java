package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player learned about the taste: the tracks switched off in the first seconds (no seed, and they rest for a
 * few days before the wave may offer them again: a skip may only mean "not now") and the tracks heard to a good length
 * (seeds of the wave). Newest last, each kept for a limited time and in a limited
 * number, so the lists do not grow for ever. Written as one line of text so it fits a preference. No Android here, so it
 * can be tested on the JVM.
 */
final class WaveLedger {
    static final int SKIPPED_LIMIT = 600;
    static final int HEARD_LIMIT = 300;
    /** A skip is forgotten after this long: a track disliked for a mood is not lost for good (a dislike is for good). */
    static final long SKIP_KEPT_MS = 90L * 24 * 3600 * 1000;
    static final long HEARD_KEPT_MS = 60L * 24 * 3600 * 1000;
    /**
     * How long a skipped track is not offered. Switching a track off in its first seconds may mean "not now" as well as
     * "not for me", so it comes back after a rest; "Not for me" is the button for good.
     */
    static final long SKIP_REST_MS = 3L * 24 * 3600 * 1000;

    private final Map<String, Long> skipped = new LinkedHashMap<>();
    private final Map<String, Long> heard = new LinkedHashMap<>();

    /** Notes a listen. A track heard later than it was skipped is no longer skipped, and the other way round. */
    synchronized void record(String urn, ListenVerdict verdict, long now) {
        switch (verdict) {
            case SKIPPED:
                heard.remove(urn);
                skipped.remove(urn);
                skipped.put(urn, now);
                break;
            case HEARD:
                skipped.remove(urn);
                heard.remove(urn);
                heard.put(urn, now);
                break;
            default:
                break;
        }
        trim(skipped, SKIPPED_LIMIT, SKIP_KEPT_MS, now);
        trim(heard, HEARD_LIMIT, HEARD_KEPT_MS, now);
    }

    private static void trim(Map<String, Long> map, int limit, long keptMs, long now) {
        for (Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > keptMs) it.remove();
        }
        for (Iterator<String> it = map.keySet().iterator(); map.size() > limit && it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    synchronized List<String> skipped() {
        return new ArrayList<>(skipped.keySet());
    }

    /** The heard tracks, the newest first. */
    synchronized List<String> heardNewestFirst() {
        List<String> urns = new ArrayList<>(heard.keySet());
        java.util.Collections.reverse(urns);
        return urns;
    }

    synchronized boolean isSkipped(String urn) {
        return skipped.containsKey(urn);
    }

    /** Whether the track was skipped less than {@link #SKIP_REST_MS} ago: the wave does not offer it now. */
    synchronized boolean isResting(String urn, long now) {
        Long time = skipped.get(urn);
        return time != null && now - time < SKIP_REST_MS;
    }

    /** The tracks skipped less than {@link #SKIP_REST_MS} ago. */
    synchronized List<String> resting(long now) {
        List<String> urns = new ArrayList<>();
        for (Map.Entry<String, Long> entry : skipped.entrySet()) if (now - entry.getValue() < SKIP_REST_MS) urns.add(entry.getKey());
        return urns;
    }

    /** {@code s<urn>=<time>} and {@code h<urn>=<time>} items, separated by spaces. */
    synchronized String format() {
        StringBuilder text = new StringBuilder();
        append(text, 's', skipped);
        append(text, 'h', heard);
        return text.toString();
    }

    private static void append(StringBuilder text, char kind, Map<String, Long> map) {
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            if (text.length() > 0) text.append(' ');
            text.append(kind).append(entry.getKey()).append('=').append(entry.getValue());
        }
    }

    /** The ledger of a text made by {@link #format}; items that are damaged are left out. */
    static WaveLedger parse(String text) {
        WaveLedger ledger = new WaveLedger();
        if (text == null || text.isEmpty()) return ledger;
        for (String item : text.split(" ")) {
            int equals = item.lastIndexOf('=');
            if (item.length() < 3 || equals < 2) continue;
            try {
                long time = Long.parseLong(item.substring(equals + 1));
                String urn = item.substring(1, equals);
                if (item.charAt(0) == 's') ledger.skipped.put(urn, time);
                else if (item.charAt(0) == 'h') ledger.heard.put(urn, time);
            } catch (NumberFormatException ignored) {
                // A damaged item is one lost track.
            }
        }
        return ledger;
    }
}
