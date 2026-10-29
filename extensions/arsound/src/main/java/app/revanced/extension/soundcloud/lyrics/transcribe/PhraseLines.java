package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the timed words of one window into the lines of a song: a new line where the singer pauses, as lyrics are
 * written. GigaAM writes no punctuation, so the pauses are the only sign of where a phrase ends; on the voice of a
 * song they cut "вся моя жизнь говно / но мне хорошо / купаться в тюрьме" the way the words are sung. A phrase too long
 * for a line (fast rap without breath) is cut at its longest pause, a lone short word joins the nearer neighbour.
 * <p>
 * Each line starts when its first word is sung. Pure logic; used by {@link SongListener}.
 */
final class PhraseLines {
    /** A pause at least this long ends a line. */
    static final long PAUSE_MS = 450;
    /** The longest line, in characters; a longer phrase is cut. */
    static final int MAX_LINE = 42;
    /** A line shorter than this joins a neighbour that is close enough. */
    static final int MIN_LINE = 8;
    /** A short line joins a neighbour only across a pause shorter than this. */
    static final long JOIN_MS = 1000;

    /** One line and the moment it is sung, in milliseconds from the start of the window. */
    static final class Line {
        final List<HeardWord> words;

        Line(List<HeardWord> words) {
            this.words = words;
        }

        long startMs() {
            return words.get(0).startMs;
        }

        long endMs() {
            return words.get(words.size() - 1).endMs;
        }

        String text() {
            return PhraseLines.text(words);
        }
    }

    private PhraseLines() {
    }

    /** The lines of the words, in order; empty for no words. */
    static List<Line> cut(List<HeardWord> words) {
        List<List<HeardWord>> lines = new ArrayList<>();
        List<HeardWord> phrase = new ArrayList<>();
        for (HeardWord word : words) {
            if (!phrase.isEmpty() && pause(phrase.get(phrase.size() - 1), word) >= PAUSE_MS) {
                fit(phrase, lines);
                phrase = new ArrayList<>();
            }
            phrase.add(word);
        }
        if (!phrase.isEmpty()) fit(phrase, lines);
        joinShort(lines);
        List<Line> result = new ArrayList<>();
        for (List<HeardWord> line : lines) result.add(new Line(line));
        return result;
    }

    /** Puts a phrase into lines, cutting it at its longest pause while it is too long. */
    private static void fit(List<HeardWord> phrase, List<List<HeardWord>> lines) {
        if (phrase.size() < 2 || length(phrase) <= MAX_LINE) {
            lines.add(phrase);
            return;
        }
        int cut = -1;
        long longest = -1;
        int unevenness = Integer.MAX_VALUE;
        for (int i = 1; i < phrase.size(); i++) {
            int left = length(phrase.subList(0, i));
            int right = length(phrase.subList(i, phrase.size()));
            long gap = pause(phrase.get(i - 1), phrase.get(i));
            // The longest pause wins; of equal pauses the one that halves the phrase most evenly.
            if (gap > longest || (gap == longest && Math.abs(left - right) < unevenness)) {
                cut = i;
                longest = gap;
                unevenness = Math.abs(left - right);
            }
        }
        fit(new ArrayList<>(phrase.subList(0, cut)), lines);
        fit(new ArrayList<>(phrase.subList(cut, phrase.size())), lines);
    }

    /** A line of a word or two joins the neighbour it is sung closer to, when that is near and the result fits. */
    private static void joinShort(List<List<HeardWord>> lines) {
        for (int i = 0; i < lines.size(); i++) {
            List<HeardWord> line = lines.get(i);
            if (length(line) >= MIN_LINE || lines.size() < 2) continue;
            long before = i > 0 ? pause(last(lines.get(i - 1)), line.get(0)) : Long.MAX_VALUE;
            long after = i + 1 < lines.size() ? pause(last(line), lines.get(i + 1).get(0)) : Long.MAX_VALUE;
            boolean toPrevious = before <= after;
            int other = toPrevious ? i - 1 : i + 1;
            if (Math.min(before, after) >= JOIN_MS || length(line) + 1 + length(lines.get(other)) > MAX_LINE) continue;
            List<HeardWord> joined = new ArrayList<>(toPrevious ? lines.get(other) : line);
            joined.addAll(toPrevious ? line : lines.get(other));
            lines.set(Math.min(i, other), joined);
            lines.remove(Math.max(i, other));
            i = Math.max(-1, Math.min(i, other) - 1);
        }
    }

    static String text(List<HeardWord> words) {
        StringBuilder text = new StringBuilder();
        for (HeardWord word : words) {
            if (text.length() > 0) text.append(' ');
            text.append(word.text);
        }
        return text.toString();
    }

    private static int length(List<HeardWord> words) {
        int length = Math.max(0, words.size() - 1);
        for (HeardWord word : words) length += word.text.length();
        return length;
    }

    private static long pause(HeardWord before, HeardWord after) {
        return after.startMs - before.endMs;
    }

    private static HeardWord last(List<HeardWord> words) {
        return words.get(words.size() - 1);
    }
}
