package app.revanced.extension.soundcloud.lyrics;

import java.util.Arrays;

/**
 * The text of one song. With timings every line starts at a moment of the track; without them it is plain text.
 * An empty line is a pause: an instrumental part with timings, a gap between verses without.
 */
final class Lyrics {
    final String[] lines;
    /** Start of each line in milliseconds, ascending; null when the text has no timings to follow. */
    final long[] times;

    Lyrics(String[] lines, long[] times) {
        this.lines = lines;
        this.times = times;
    }

    boolean isTimed() {
        return times != null;
    }

    /**
     * The line that sounds at a moment: the last one that has started.
     *
     * @return -1 before the first line, and always for text without timings.
     */
    int indexAt(long positionMs) {
        if (times == null) return -1;
        int low = 0;
        int high = times.length - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= positionMs) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    /** Where the last line starts, in milliseconds; 0 for text without timings. */
    long lastTime() {
        return times == null || times.length == 0 ? 0 : times[times.length - 1];
    }

    /** The same words as plain text: for a record whose timings do not fit the track that plays. */
    Lyrics withoutTimes() {
        return times == null ? this : new Lyrics(lines, null);
    }

    boolean sameAs(Lyrics other) {
        return other != null && Arrays.equals(lines, other.lines) && Arrays.equals(times, other.times);
    }
}
