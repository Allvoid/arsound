package app.revanced.extension.soundcloud.lyrics;

/** What the search for the lyrics of one track came to. */
final class LyricsResult {
    enum Status {
        /** The text is in {@link #lyrics}. */
        FOUND,
        /** LRCLIB knows the track and says it has no words. */
        INSTRUMENTAL,
        /** LRCLIB has no record of the track. */
        MISSING,
        /** No network and nothing stored. Never stored. */
        OFFLINE,
        /** The search failed (server, connection, blocked region). Never stored. */
        ERROR
    }

    private static final LyricsResult INSTRUMENTAL = new LyricsResult(Status.INSTRUMENTAL, null);
    private static final LyricsResult MISSING = new LyricsResult(Status.MISSING, null);
    private static final LyricsResult OFFLINE = new LyricsResult(Status.OFFLINE, null);
    private static final LyricsResult ERROR = new LyricsResult(Status.ERROR, null);

    final Status status;
    final Lyrics lyrics;

    private LyricsResult(Status status, Lyrics lyrics) {
        this.status = status;
        this.lyrics = lyrics;
    }

    static LyricsResult found(Lyrics lyrics) {
        return new LyricsResult(Status.FOUND, lyrics);
    }

    static LyricsResult instrumental() {
        return INSTRUMENTAL;
    }

    static LyricsResult missing() {
        return MISSING;
    }

    static LyricsResult offline() {
        return OFFLINE;
    }

    static LyricsResult error() {
        return ERROR;
    }

    /** Whether the answer is worth keeping on the phone: a failure is not an answer. */
    boolean isStorable() {
        return status == Status.FOUND || status == Status.INSTRUMENTAL || status == Status.MISSING;
    }
}
