package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Puts times on a text the user pasted. Pure Java: no Android, covered by JUnit tests.
 * <p>
 * With a recognised transcript of the song (lines the neural network heard, each with the moment it starts) the pasted
 * lines are matched to the heard words one after another: a pasted line is looked for ahead of the last one found,
 * with words that may be a letter or two off, as the network mishears. A line that was found takes the time of its
 * first heard word, and the lines between two found ones share the time between them by their length. With nothing
 * heard, or too little of it matching, the lines are spread over the length of the track, after a short intro and
 * before a short outro, each taking time by its length: the timing is a guess, and the result says so
 * ({@link Mode#APPROXIMATE}).
 * <p>
 * Without the length of the track and without a transcript the text stays plain, with no times.
 */
final class LyricsAligner {
    /** How the times came to be. */
    enum Mode {
        /** From the words a neural network heard in the song. */
        HEARD,
        /** Spread over the track: only a guess. */
        APPROXIMATE,
        /** No times at all. */
        PLAIN
    }

    /** The lines to show and their times (null for {@link Mode#PLAIN}). */
    static final class Result {
        final String[] lines;
        final long[] times;
        final Mode mode;

        Result(String[] lines, long[] times, Mode mode) {
            this.lines = lines;
            this.times = times;
            this.mode = mode;
        }
    }

    /** The share of lines that must be found among the heard words for the times to be called "by the words". */
    private static final double HEARD_SHARE = 0.5;
    /** Fewer lines than this share found: the match is chance, and the lines are spread over the track instead. */
    private static final double MIN_SHARE = 0.25;
    /** A line is found when this share of its words is found in order. */
    private static final double LINE_SCORE = 0.5;
    /** How many heard words ahead of the last found line a line is looked for. */
    private static final int LOOKAHEAD = 80;
    private static final long MIN_STEP_MS = 300;
    /** A repeat mark in parentheses, "(x2)" or "(2x)": it is not sung, so it takes no time. */
    private static final Pattern REPEAT_MARK = Pattern.compile("x\\s*\\d+|\\d+\\s*x", Pattern.CASE_INSENSITIVE);
    /**
     * Words that name a part of a song in parentheses, "(Chorus)": not sung. Other parenthesised lines are sung, such as
     * "(oh yeah, oh yeah)", and stay.
     */
    private static final Set<String> PART_WORDS = new HashSet<>(Arrays.asList(
            "intro", "outro", "interlude", "verse", "chorus", "hook", "bridge", "pre-chorus", "prechorus",
            "интро", "аутро", "проигрыш", "куплет", "припев", "бридж", "пре-припев"));

    private LyricsAligner() {
    }

    /** The lines of a pasted text: trimmed, the runs of empty lines made one, none empty at the ends. */
    static List<String> split(String pasted) {
        List<String> lines = new ArrayList<>();
        if (pasted == null) return lines;
        for (String raw : pasted.replace("\r", "").split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() && (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty())) continue;
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /**
     * @param pasted     The text the user pasted.
     * @param heardLines The lines a neural network heard in the song, or null.
     * @param heardTimes Their start times in milliseconds, ascending, as many as the lines; null with no lines.
     * @param durationMs The length of the track, 0 when unknown.
     */
    static Result align(String pasted, String[] heardLines, long[] heardTimes, long durationMs) {
        List<String> all = split(pasted);
        // Section marks ("[Chorus]") are not sung: they take no time on a timed text.
        List<String> content = new ArrayList<>();
        for (String line : all) {
            if (!line.isEmpty() && !isSectionMark(line)) content.add(line);
        }
        boolean heard = heardLines != null && heardTimes != null && heardLines.length >= 3
                && heardLines.length == heardTimes.length;
        if (content.isEmpty() || (!heard && durationMs <= 0)) {
            return new Result(all.toArray(new String[0]), null, Mode.PLAIN);
        }
        String[] lines = content.toArray(new String[0]);
        if (heard) {
            Result byWords = fromHeard(lines, heardLines, heardTimes, durationMs);
            if (byWords != null) return byWords;
        }
        if (durationMs <= 0) return new Result(all.toArray(new String[0]), null, Mode.PLAIN);
        return new Result(lines, steady(spread(lines, durationMs), durationMs), Mode.APPROXIMATE);
    }

    private static boolean isSectionMark(String line) {
        if (line.startsWith("[") && line.endsWith("]")) return true;
        if (!line.startsWith("(") || !line.endsWith(")")) return false;
        String inside = line.substring(1, line.length() - 1).trim();
        return REPEAT_MARK.matcher(inside).matches() || PART_WORDS.contains(inside.toLowerCase(Locale.ROOT));
    }

    // region Spreading

    private static long[] spread(String[] lines, long durationMs) {
        long intro = Math.max(4_000, Math.min(15_000, Math.round(durationMs * 0.05)));
        long outro = Math.max(2_000, Math.min(10_000, Math.round(durationMs * 0.03)));
        long span = durationMs - intro - outro;
        if (span < 10_000) {
            intro = 0;
            span = durationMs;
        }
        double total = 0;
        for (String line : lines) total += weight(line);
        long[] times = new long[lines.length];
        double sum = 0;
        for (int i = 0; i < lines.length; i++) {
            times[i] = intro + Math.round(span * sum / total);
            sum += weight(lines[i]);
        }
        return times;
    }

    /** What a line costs in time: its length, with a floor, as a short line is still sung for a moment. */
    private static double weight(String line) {
        return Math.max(8, line.length());
    }

    // endregion

    // region Matching to the heard words

    /**
     * The lines with their times by the heard words, or null when too few lines are found. Called "by the words" when
     * half of the lines or more were found, a guess helped by some anchors otherwise.
     */
    private static Result fromHeard(String[] lines, String[] heardLines, long[] heardTimes, long durationMs) {
        List<String> heardWords = new ArrayList<>();
        List<Long> heardAt = new ArrayList<>();
        for (int i = 0; i < heardLines.length; i++) {
            String[] words = words(heardLines[i]);
            long start = heardTimes[i];
            long end = i + 1 < heardLines.length ? heardTimes[i + 1]
                    : (durationMs > start ? Math.min(durationMs, start + 6_000) : start + 4_000);
            for (int k = 0; k < words.length; k++) {
                heardWords.add(words[k]);
                heardAt.add(start + (end - start) * k / Math.max(1, words.length));
            }
        }
        if (heardWords.size() < 6) return null;

        long[] anchors = new long[lines.length];
        boolean[] found = new boolean[lines.length];
        int foundCount = 0;
        int pointer = 0;
        for (int j = 0; j < lines.length; j++) {
            String[] words = words(lines[j]);
            if (words.length == 0) continue;
            int bestStart = -1;
            int bestMatched = 0;
            int last = Math.min(heardWords.size() - 1, pointer + LOOKAHEAD);
            for (int s = pointer; s <= last; s++) {
                int matched = matchedAt(words, heardWords, s);
                if (matched > bestMatched) {
                    bestMatched = matched;
                    bestStart = s;
                }
            }
            double score = bestMatched / (double) words.length;
            // A one-word line is found only exactly, and only if it is the word right there.
            boolean ok = bestStart >= 0 && (words.length == 1 ? bestMatched == 1 && bestStart <= pointer + 6 : score >= LINE_SCORE);
            if (!ok) continue;
            anchors[j] = heardAt.get(bestStart);
            found[j] = true;
            foundCount++;
            pointer = bestStart + Math.max(1, bestMatched);
        }
        if (foundCount < Math.max(2, Math.ceil(lines.length * MIN_SHARE))) return null;
        long lower = Math.min(heardAt.get(0), firstAnchor(anchors, found));
        long upper = durationMs > 0 ? durationMs : heardAt.get(heardAt.size() - 1) + 4_000;
        long[] times = fill(lines, anchors, found, Math.max(0, lower), upper);
        return new Result(lines, steady(times, durationMs),
                foundCount >= lines.length * HEARD_SHARE ? Mode.HEARD : Mode.APPROXIMATE);
    }

    private static long firstAnchor(long[] anchors, boolean[] found) {
        for (int i = 0; i < anchors.length; i++) if (found[i]) return anchors[i];
        return 0;
    }

    /** How many words of the line are found in order from the heard word {@code start}, each within a few words of the last. */
    private static int matchedAt(String[] words, List<String> heard, int start) {
        int matched = 0;
        int at = start;
        int misses = 0;
        for (String word : words) {
            boolean hit = false;
            for (int k = at; k < Math.min(heard.size(), at + 3); k++) {
                if (same(word, heard.get(k))) {
                    matched++;
                    at = k + 1;
                    hit = true;
                    break;
                }
            }
            if (!hit && ++misses > words.length) break;
        }
        // The line must begin where it is said to: its first word is among the first ones found.
        return same(words[0], heard.get(start)) || matched * 2 >= words.length ? matched : 0;
    }

    /**
     * Times for the lines between the found ones: they share the time between their neighbours by the length of the
     * lines. {@code lower} is the moment before the first found line, {@code upper} the end after the last.
     */
    private static long[] fill(String[] lines, long[] anchors, boolean[] found, long lower, long upper) {
        long[] times = new long[lines.length];
        int previous = -1;
        long previousTime = lower;
        for (int next = 0; next <= lines.length; next++) {
            if (next < lines.length && !found[next]) continue;
            long nextTime = next < lines.length ? anchors[next] : upper;
            if (nextTime < previousTime) nextTime = previousTime;
            // The time between the two found lines goes to the lines from the earlier one up to the later one's
            // predecessor, by their length; the earlier found line itself takes its share first.
            double total = 0;
            for (int k = Math.max(previous, 0); k < next; k++) total += weight(lines[k]);
            double sum = previous >= 0 ? weight(lines[previous]) : 0;
            for (int k = previous + 1; k < next; k++) {
                times[k] = previousTime + Math.round((nextTime - previousTime) * (total <= 0 ? 0 : sum / total));
                sum += weight(lines[k]);
            }
            if (next < lines.length) {
                times[next] = nextTime;
                previous = next;
                previousTime = nextTime;
            }
        }
        return times;
    }

    private static String[] words(String line) {
        String normal = TextMatch.normalize(line);
        return normal.isEmpty() ? new String[0] : normal.split(" ");
    }

    /** The same word, give or take what a network mishears: one letter more, less or other in a word of four or more. */
    static boolean same(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < 4 || b.length() < 4 || Math.abs(a.length() - b.length()) > 1) return false;
        return withinOne(a, b);
    }

    private static boolean withinOne(String a, String b) {
        int i = 0, j = 0, difference = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++difference > 1) return false;
            if (a.length() == b.length()) {
                i++;
                j++;
            } else if (a.length() > b.length()) {
                i++;
            } else {
                j++;
            }
        }
        return difference + (a.length() - i) + (b.length() - j) <= 1;
    }

    // endregion

    /** Times that never go back and never stand still, and none past the end of the track. */
    private static long[] steady(long[] times, long durationMs) {
        long[] result = times.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] < 0) result[i] = 0;
            if (i > 0 && result[i] < result[i - 1] + MIN_STEP_MS) result[i] = result[i - 1] + MIN_STEP_MS;
        }
        if (durationMs > 0) {
            long limit = durationMs - 1;
            int first = 0;
            while (first < result.length && result[first] <= limit) first++;
            if (first < result.length) {
                // The steps pushed the last lines past the end. That run is fitted between the line before it and the
                // end of the track, with as much room as there is, so that the lines stay in order and inside the track.
                long before = first > 0 ? result[first - 1] : 0;
                long count = result.length - first;
                long step = Math.min(MIN_STEP_MS, Math.max(0, limit - before) / count);
                for (int i = first; i < result.length; i++) result[i] = before + step * (i - first + 1);
            }
        }
        return result;
    }
}
