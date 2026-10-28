package app.revanced.extension.soundcloud.lyrics;

/** A record of the LRCLIB database: one version of one song, with the text it was uploaded with. */
final class LrclibRecord {
    final long id;
    final String trackName;
    final String artistName;
    /** Seconds, or NaN when the record has none. */
    final double durationSeconds;
    final boolean instrumental;
    final String plain;
    final String synced;

    LrclibRecord(long id, String trackName, String artistName, double durationSeconds, boolean instrumental,
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
