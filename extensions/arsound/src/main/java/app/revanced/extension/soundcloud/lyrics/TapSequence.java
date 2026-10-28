package app.revanced.extension.soundcloud.lyrics;

/**
 * Tells the first tap on a line of the words from the second of a double tap. Pure, so it runs in the JVM tests.
 * <p>
 * A tap that is not followed by another within the window is single. The tap that follows within the window is the
 * second of a double tap and is used up by it: a third tap starts a new pair.
 */
final class TapSequence {
    /** What a tap is, given the one before it. */
    enum Kind {
        SINGLE,
        DOUBLE
    }

    private static final long NONE = Long.MIN_VALUE;

    private final long windowMs;
    /** When the last single tap was; {@link #NONE} once it has been used up by a double tap or {@link #reset}. */
    private long lastSingleAt = NONE;

    /**
     * @param windowMs The longest time between the two taps of a double tap.
     */
    TapSequence(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Takes a tap.
     *
     * @param timeMs When it happened, in the clock of the touch events.
     */
    Kind next(long timeMs) {
        boolean second = lastSingleAt != NONE && timeMs - lastSingleAt >= 0 && timeMs - lastSingleAt <= windowMs;
        lastSingleAt = second ? NONE : timeMs;
        return second ? Kind.DOUBLE : Kind.SINGLE;
    }

    /** A tap that is not part of any pair came in between: the next tap on a line starts afresh. */
    void reset() {
        lastSingleAt = NONE;
    }
}
