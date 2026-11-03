package app.revanced.extension.soundcloud.recommendations;

/**
 * What one listen says about the taste. Switched off in the first seconds is a no: such a track is never offered again
 * and is no seed of the wave. Heard to a good length (45 seconds, or most of a short track) is a yes. In between says
 * nothing. No Android here, so it can be tested on the JVM.
 */
public enum ListenVerdict {
    SKIPPED,
    BETWEEN,
    HEARD;

    /** A track switched off before this is skipped. */
    public static final long SKIP_BELOW_MS = 15_000;
    /** A track played this long is heard. */
    public static final long HEARD_FROM_MS = 45_000;

    /**
     * @param playedMs   How long the track really played.
     * @param durationMs The length of the track, 0 if not known.
     */
    public static ListenVerdict of(long playedMs, long durationMs) {
        // A track shorter than the skip limit is never "skipped" by playing all of it.
        if (durationMs > 0 && playedMs >= durationMs * 9 / 10) return HEARD;
        if (playedMs < SKIP_BELOW_MS) return SKIPPED;
        if (playedMs >= HEARD_FROM_MS) return HEARD;
        // A short track heard through its larger part is heard too.
        return durationMs > 0 && playedMs * 100 >= durationMs * 60 ? HEARD : BETWEEN;
    }
}
