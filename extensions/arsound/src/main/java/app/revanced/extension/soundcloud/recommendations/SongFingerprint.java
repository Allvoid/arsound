package app.revanced.extension.soundcloud.recommendations;

import java.util.List;

/** A song as the duplicate filter compares it: a normalized title key and a length. */
final class SongFingerprint {
    static final long DURATION_TOLERANCE_MS = 2_000;

    final String key;
    final long duration;
    /** Slowed, sped up, remixed and similar: another speed changes the duration. */
    private final boolean edited;
    /** Keyed with edited versions merged into the original. */
    private final boolean mergeEdited;

    SongFingerprint(String key, long duration, boolean edited, boolean mergeEdited) {
        this.key = key;
        this.duration = duration;
        this.edited = edited;
        this.mergeEdited = mergeEdited;
    }

    boolean sameAs(SongFingerprint other) {
        if (key.isEmpty() || !key.equals(other.key)) return false;
        // Merged versions have the same key; their durations differ with the speed.
        if ((edited || other.edited) && mergeEdited && other.mergeEdited) return true;
        return Math.abs(duration - other.duration) <= DURATION_TOLERANCE_MS;
    }

    static boolean containsSame(List<SongFingerprint> songs, SongFingerprint song) {
        for (SongFingerprint kept : songs) if (kept.sameAs(song)) return true;
        return false;
    }
}
