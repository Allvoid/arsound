package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.List;

/**
 * Gives the lines of one window the moments they start at, for a model that does not tell when its words are sung
 * (Whisper): the lines are spread over the part of the window where sound is, each taking time in proportion to its
 * length. The error is a second or two. GigaAM tells the moment of every letter, its lines are timed by
 * {@link PhraseLines} instead.
 * <p>
 * Used by {@link SongListener}; the span of the sound comes from {@link Chunker#activeSpan}.
 */
final class LineTimer {
    private LineTimer() {
    }

    /**
     * @param lines        The lines of one window, in order; at least one.
     * @param startMs      When the sound starts, in milliseconds from the start of the song.
     * @param endMs        When the sound ends.
     * @param notBeforeMs  No line starts before this (the end of the previous window's first line).
     * @return The start of each line, ascending and at least a little apart.
     */
    static long[] spread(List<String> lines, long startMs, long endMs, long notBeforeMs) {
        int count = lines.size();
        long[] times = new long[count];
        long span = Math.max(1000L * count, endMs - startMs);
        double total = 0;
        for (String line : lines) total += weight(line);
        double before = 0;
        long previous = notBeforeMs - 1;
        for (int i = 0; i < count; i++) {
            long time = Math.max(startMs, notBeforeMs) + Math.round(span * (before / total));
            // Never earlier than the line before, so the order of the lines is the order of the clock.
            time = Math.max(time, previous + 1);
            times[i] = time;
            previous = time;
            before += weight(lines.get(i));
        }
        return times;
    }

    /** A line takes time by the letters it has; a short one still takes some. */
    private static double weight(String line) {
        return 6 + line.length();
    }
}
