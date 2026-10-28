package app.revanced.extension.soundcloud.recommendations;

/** How a "For you" refresh ended: the outcome the midnight job acts on, and the line the settings show. */
final class RefreshResult {
    enum Outcome {
        /** The playlist was filled. */
        FILLED,
        /** Nothing to do until tomorrow: no key, no seeds, nothing new. */
        DONE,
        /** Last.fm or SoundCloud could not be reached; worth trying again soon. */
        RETRY
    }

    final Outcome outcome;
    final String message;

    private RefreshResult(Outcome outcome, String message) {
        this.outcome = outcome;
        this.message = message;
    }

    static RefreshResult filled(String message) {
        return new RefreshResult(Outcome.FILLED, message);
    }

    /** A result after which trying again today would not help. */
    static RefreshResult done(String message) {
        return new RefreshResult(Outcome.DONE, message);
    }

    /** The refresh did not get anywhere; the next try may. */
    static RefreshResult retry(String message) {
        return new RefreshResult(Outcome.RETRY, message);
    }

    /** True if nothing is left for the day: the playlist was filled, or there was nothing to add. */
    boolean settlesTheDay() {
        return outcome != Outcome.RETRY;
    }
}
