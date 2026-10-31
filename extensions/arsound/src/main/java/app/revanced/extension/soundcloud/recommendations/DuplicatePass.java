package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One pass over a list of tracks: the first copy of a song stays, disliked tracks and later copies go. Songs and tracks
 * that are in the list from the start (the track autoplay was asked for, the play queue) count as first copies.
 */
final class DuplicatePass {
    private final Predicate<Object> isDisliked;
    /** Tracks that are in the list already, by normalized urn. */
    private final Set<String> keptTracks = new HashSet<>();
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
        remember(song);
    }

    /** A track that is in the list already, such as one waiting in the play queue: it is not added again. */
    final void keepTrack(String urn) {
        keptTracks.add(urn);
    }

    /**
     * Whether the track is one kept by {@link #keepTrack}; such a track is counted as taken out.
     *
     * @param urn The track urn, or null.
     */
    final boolean hasTrack(Object urn) {
        if (urn == null || !keptTracks.contains(TrackUrn.normalize(urn))) return false;
        removed++;
        return true;
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
        remember(song);
        return true;
    }

    /** A song that stays, under its key: the seeded songs too, or their copies went through. */
    private void remember(SongFingerprint song) {
        keptByKey.computeIfAbsent(song.key, key -> new ArrayList<>()).add(song);
    }

    /** Whether the song is a copy of one that stays: only songs of the same key can be. */
    private boolean isCopy(SongFingerprint song) {
        List<SongFingerprint> sameKey = keptByKey.get(song.key);
        return sameKey != null && SongFingerprint.containsSame(sameKey, song);
    }

    /** The songs that stay, by their key: a list of hundreds was gone through for every entry. */
    private final java.util.Map<String, List<SongFingerprint>> keptByKey = new java.util.HashMap<>();
}
