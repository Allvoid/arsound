package app.revanced.extension.soundcloud.scrobble;

/**
 * The listen that was counted last, kept as a short text next to the scrobbler's queues. A listen that is taken up again
 * after the app was closed (a pause, then a restart) starts the scrobbler's bookkeeping from nothing; this text is what
 * tells it that the listen was counted already, so it is not sent a second time with another start time.
 */
final class CountedListen {
    /** The start times of the same listen differ by what lies between two reads of the clock. */
    static final long TOLERANCE_SECONDS = 5;

    private CountedListen() {
    }

    static String mark(String urn, long startedAtSeconds) {
        return startedAtSeconds + "\t" + urn;
    }

    /** Whether the kept mark is of this very listen: the same track that began at the same time. */
    static boolean matches(String mark, String urn, long startedAtSeconds) {
        if (mark == null || urn == null) return false;
        int tab = mark.indexOf('\t');
        if (tab <= 0) return false;
        try {
            long kept = Long.parseLong(mark.substring(0, tab));
            return mark.substring(tab + 1).equals(urn) && Math.abs(kept - startedAtSeconds) <= TOLERANCE_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
