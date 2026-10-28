package app.revanced.extension.soundcloud.player;

/**
 * One listen: when the track started, which track, and how many milliseconds of it really played. The same three
 * values, tab-separated, make a line of the history file and the saved mark of the listen in progress, so both are
 * read and written here. Existing files carry this format: it must not change.
 */
final class ListenRecord {
    final long start;
    final String urn;
    final long playedMs;

    ListenRecord(long start, String urn, long playedMs) {
        this.start = start;
        this.urn = urn;
        this.playedMs = playedMs;
    }

    /** The text of the record, without a line end. */
    String format() {
        return start + "\t" + urn + "\t" + playedMs;
    }

    /** @return The record in the text, or null if it is damaged (not three parts, or numbers that are not numbers). */
    static ListenRecord parse(String text) {
        String[] parts = text.split("\t");
        if (parts.length != 3) return null;
        try {
            return new ListenRecord(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The start and the track of a line, for a reader that does not need the time played: a line damaged only in
     * its last part still says when the track was listened to.
     *
     * @return A record with 0 played, or null if the line has not three parts or no start time.
     */
    static ListenRecord parseStartAndUrn(String text) {
        String[] parts = text.split("\t");
        if (parts.length != 3) return null;
        try {
            return new ListenRecord(Long.parseLong(parts[0]), parts[1], 0);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
