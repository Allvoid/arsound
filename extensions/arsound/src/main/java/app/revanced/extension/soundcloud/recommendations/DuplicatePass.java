package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One pass over a list of tracks: the first copy of a song stays, disliked tracks and later copies go. The lists
 * differ in how a track is read and in what else counts as a copy, which subclasses answer.
 */
class DuplicatePass {
    private final Predicate<Object> isDisliked;
    private final List<SongFingerprint> kept = new ArrayList<>();
    private int removed;

    DuplicatePass() {
        this(TrackDislikes::isDisliked);
    }

    /** @param isDisliked Tells whether a track urn (possibly null) is one the user does not want. */
    DuplicatePass(Predicate<Object> isDisliked) {
        this.isDisliked = isDisliked;
    }

    /** The number of tracks the pass has taken out. */
    final int removed() {
        return removed;
    }

    /** A song that stays in the list from the start, such as the track autoplay was asked for. */
    final void keep(SongFingerprint song) {
        kept.add(song);
    }

    /**
     * The first question about a track: is it disliked? A disliked track is counted as taken out.
     *
     * @param urn The track urn, or null.
     */
    final boolean dislikes(Object urn) {
        if (!isDisliked.test(urn)) return false;
        removed++;
        return true;
    }

    /**
     * The second question, for a track that is not disliked: does it stay? A copy of a song that stays is counted
     * as taken out; a song that stays is remembered.
     *
     * @param song The song to compare, or null when it is not compared (duplicates off, or no title known).
     */
    final boolean keeps(SongFingerprint song) {
        if (song == null) return true;
        if (isCopy(song)) {
            removed++;
            return false;
        }
        kept.add(song);
        onKept(song);
        return true;
    }

    /** Whether the song is a copy of one that stays. */
    boolean isCopy(SongFingerprint song) {
        return SongFingerprint.containsSame(kept, song);
    }

    /** Called for each song that stays. */
    void onKept(SongFingerprint song) {
    }

    /**
     * Autoplay refills the queue again and again: songs it queued lately are copies too, so a later refill does not
     * bring back another upload of a song that is already waiting.
     */
    static final class Autoplay extends DuplicatePass {
        private static final int MEMORY = 300;

        /** Song key to length of the songs recently queued, oldest first. */
        private static final Map<String, Long> queued = new LinkedHashMap<String, Long>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MEMORY;
            }
        };

        Autoplay() {
            super();
        }

        Autoplay(Predicate<Object> isDisliked) {
            super(isDisliked);
        }

        @Override
        boolean isCopy(SongFingerprint song) {
            return super.isCopy(song) || recentlyQueued(song);
        }

        @Override
        void onKept(SongFingerprint song) {
            synchronized (queued) {
                queued.put(song.key, song.duration);
            }
        }

        private static boolean recentlyQueued(SongFingerprint song) {
            synchronized (queued) {
                Long duration = queued.get(song.key);
                return duration != null && !song.key.isEmpty()
                        && Math.abs(duration - song.duration) <= SongFingerprint.DURATION_TOLERANCE_MS;
            }
        }
    }
}
