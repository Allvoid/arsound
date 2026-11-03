package app.revanced.extension.soundcloud.lyrics;

import app.revanced.extension.soundcloud.shared.Lang;

/** What the search for the lyrics of one track came to. */
final class LyricsResult {
    /**
     * The kinds of answer. Each one knows what the page says for it, whether it is worth keeping and whether the
     * user can ask again, so no code has to switch over the kinds.
     */
    enum Status {
        /** The text is in {@link #lyrics}. */
        FOUND("found", true, false, null, null),
        /** LRCLIB knows the track and says it has no words. */
        INSTRUMENTAL("instrumental", true, false, "Инструментальный трек", "Instrumental track"),
        /** LRCLIB has no record of the track. */
        MISSING("missing", true, false, "Текст не найден", "No lyrics found"),
        /** No network and nothing stored. Never stored. */
        OFFLINE(null, false, true, "Нет подключения.\nНажмите, чтобы повторить", "No connection.\nTap to try again"),
        /** The search failed (server, connection, blocked region). Never stored. */
        ERROR(null, false, true, "Не удалось загрузить текст.\nНажмите, чтобы повторить",
                "Could not load the lyrics.\nTap to try again");

        /** The word the answer is stored under on the phone (files already written use these); null if never stored. */
        final String cacheKey;
        /** Whether the answer is worth keeping on the phone: a failure is not an answer. */
        final boolean storable;
        /** Whether a tap on the message looks the track up again. */
        final boolean retryable;
        private final String russian;
        private final String english;

        Status(String cacheKey, boolean storable, boolean retryable, String russian, String english) {
            this.cacheKey = cacheKey;
            this.storable = storable;
            this.retryable = retryable;
            this.russian = russian;
            this.english = english;
        }

        /** What the page says when there are no words to show; null for {@link #FOUND}. */
        String message() {
            return russian == null ? null : Lang.text(russian, english);
        }

        /** The answer stored under this word, or null for a word nobody writes. */
        static Status fromCacheKey(String key) {
            for (Status status : values()) {
                if (status.cacheKey != null && status.cacheKey.equals(key)) return status;
            }
            return null;
        }
    }

    private static final LyricsResult INSTRUMENTAL = new LyricsResult(Status.INSTRUMENTAL, null);
    private static final LyricsResult MISSING = new LyricsResult(Status.MISSING, null);
    private static final LyricsResult OFFLINE = new LyricsResult(Status.OFFLINE, null);
    private static final LyricsResult ERROR = new LyricsResult(Status.ERROR, null);

    final Status status;
    final Lyrics lyrics;
    /** Shown, but kept neither on the phone nor in memory: the next load looks the track up again. */
    final boolean stopgap;

    private LyricsResult(Status status, Lyrics lyrics) {
        this(status, lyrics, false);
    }

    private LyricsResult(Status status, Lyrics lyrics, boolean stopgap) {
        this.status = status;
        this.lyrics = lyrics;
        this.stopgap = stopgap;
    }

    static LyricsResult found(Lyrics lyrics) {
        return new LyricsResult(Status.FOUND, lyrics);
    }

    /** Words that stand in for better ones a place that did not answer may have. */
    static LyricsResult stopgap(Lyrics lyrics) {
        return new LyricsResult(Status.FOUND, lyrics, true);
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
        return status.storable && !stopgap;
    }
}
