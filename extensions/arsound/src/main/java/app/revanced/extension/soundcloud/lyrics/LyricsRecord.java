package app.revanced.extension.soundcloud.lyrics;

/**
 * One version of one song as a lyrics service lists it (an LRCLIB record, a NetEase song), with its text: synced,
 * plain, or none yet (a NetEase song before its text is fetched).
 */
final class LyricsRecord {
    final long id;
    final String trackName;
    final String artistName;
    /** Seconds, or NaN when the record has none. */
    final double durationSeconds;
    final boolean instrumental;
    final String plain;
    final String synced;

    LyricsRecord(long id, String trackName, String artistName, double durationSeconds, boolean instrumental,
                 String plain, String synced) {
        this.id = id;
        this.trackName = trackName;
        this.artistName = artistName;
        this.durationSeconds = durationSeconds;
        this.instrumental = instrumental;
        this.plain = plain;
        this.synced = synced;
    }

    boolean hasText() {
        return !isEmpty(plain) || !isEmpty(synced);
    }

    private static boolean isEmpty(String text) {
        return text == null || text.trim().isEmpty();
    }
}
