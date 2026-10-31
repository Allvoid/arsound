package app.revanced.extension.soundcloud.lyrics;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The text of one song. With timings every line starts at a moment of the track; without them it is plain text.
 * An empty line is a pause: an instrumental part with timings, a gap between verses without.
 */
final class Lyrics {
    /** Who the words come from; each knows the line of credit the page shows under them. */
    enum Source {
        /** Written by people, found on LRCLIB. */
        LRCLIB("Тексты: LRCLIB", "Lyrics: LRCLIB"),
        /** Found on NetEase Cloud Music when LRCLIB had none or could not be reached. */
        NETEASE("Тексты: NetEase Cloud Music", "Lyrics: NetEase Cloud Music"),
        /** Found on Kugou when LRCLIB and NetEase had none. */
        KUGOU("Тексты: Kugou", "Lyrics: Kugou"),
        /** Found on lyrics.ovh, the last place asked: plain words without timings. */
        OVH("Тексты: lyrics.ovh", "Lyrics: lyrics.ovh"),
        /** Heard by the neural network on the phone, so it may be wrong. */
        NEURAL("Распознано нейросетью · возможны ошибки", "Recognised by a neural network · may contain errors");

        private final String russian;
        private final String english;

        Source(String russian, String english) {
            this.russian = russian;
            this.english = english;
        }

        String caption() {
            return Lang.text(russian, english);
        }
    }

    final String[] lines;
    /** Start of each line in milliseconds, ascending; null when the text has no timings to follow. */
    final long[] times;
    final Source source;

    /** Words from LRCLIB. */
    Lyrics(String[] lines, long[] times) {
        this(lines, times, Source.LRCLIB);
    }

    Lyrics(String[] lines, long[] times, Source source) {
        this.lines = lines;
        this.times = times;
        this.source = source;
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

    /** The same words with every timing multiplied: for a slowed or sped up copy of the song (see {@link TempoMatch}). */
    Lyrics stretched(double factor) {
        if (times == null || factor == 1) return this;
        long[] scaled = new long[times.length];
        for (int i = 0; i < times.length; i++) scaled[i] = Math.round(times[i] * factor);
        return new Lyrics(lines, scaled, source);
    }

    /** The same words as plain text: for a record whose timings do not fit the track that plays. */
    Lyrics withoutTimes() {
        return times == null ? this : new Lyrics(lines, null, source);
    }

    /** The same words credited to another source. */
    Lyrics withSource(Source other) {
        return other == source ? this : new Lyrics(lines, times, other);
    }
}
