package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds and cuts the loops of the Whisper decoder: a phrase said again and again to the end of the window, a chain of
 * numbers such as "17-19-19-19-...", a run of Japanese characters without spaces. A phrase repeated a few times is a
 * chorus and stays; from five repeats (a single word: six) it is a loop and is kept twice.
 * <p>
 * Also says whether a window is mostly loop, which makes its other words unreliable too. Pure text work; used by
 * {@link TextCleaner}.
 */
final class Loops {
    /** The longest phrase, in words, that is looked for as a loop. */
    private static final int WORD_PERIODS = 12;
    /** The longest piece, in characters, of a run without spaces. */
    private static final int CHAR_PERIODS = 8;
    /** The longest piece, in parts, of a chain with dashes. */
    private static final int CHAIN_PERIODS = 3;
    /** Repeats of a phrase that make a loop. */
    private static final int PHRASE_REPEATS = 5;
    /** Repeats of a single word or part of a chain that make a loop. */
    private static final int WORD_REPEATS = 6;
    /** Repeats of a single character in a run without spaces that make a loop. */
    private static final int CHAR_REPEATS = 8;
    /** A word shorter than this is not searched for runs of characters. */
    private static final int MIN_RUN_LENGTH = 10;
    /** A window of which this share of the letters is loop is a loop as a whole. */
    private static final double MOSTLY = 0.6;
    /** A window of at least this many words with fewer than {@link #MIN_VARIETY} different ones is a loop as well. */
    private static final int VARIETY_MIN_WORDS = 40;
    private static final double MIN_VARIETY = 0.15;

    /** The text without its loops, and whether the loops were most of it. */
    static final class Result {
        final String text;
        final boolean mostlyLoop;

        Result(String text, boolean mostlyLoop) {
            this.text = text;
            this.mostlyLoop = mostlyLoop;
        }
    }

    private Loops() {
    }

    /** The text with every loop cut down to two repeats. */
    static String cut(String text) {
        return analyse(text).text;
    }

    static Result analyse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return new Result("", false);
        List<String> words = new ArrayList<>();
        int before = 0;
        for (String word : trimmed.split("\\s+")) {
            before += size(word);
            words.add(cutWord(word));
        }
        List<String> keys = new ArrayList<>();
        Set<String> different = new HashSet<>();
        for (String word : words) {
            String key = TextCleaner.plain(word);
            keys.add(key);
            if (!key.isEmpty()) different.add(key);
        }
        boolean flat = words.size() >= VARIETY_MIN_WORDS && different.size() < MIN_VARIETY * words.size();
        List<String> kept = cutRuns(words, keys, WORD_PERIODS, WORD_REPEATS, PHRASE_REPEATS);
        int after = 0;
        for (String word : kept) after += size(word);
        boolean mostly = flat || (before > 0 && before - after >= MOSTLY * before);
        return new Result(String.join(" ", kept), mostly);
    }

    /** Loops inside one word: a chain of parts with dashes, or a long run of characters without spaces. */
    private static String cutWord(String word) {
        String result = word;
        if (word.indexOf('-') > 0) {
            List<String> parts = Arrays.asList(word.split("-", -1));
            if (parts.size() >= WORD_REPEATS) {
                List<String> keys = new ArrayList<>();
                for (String part : parts) keys.add(TextCleaner.plain(part));
                result = String.join("-", cutRuns(parts, keys, CHAIN_PERIODS, WORD_REPEATS, PHRASE_REPEATS));
            }
        }
        if (result.length() >= MIN_RUN_LENGTH && hasOtherScript(result)) {
            List<String> chars = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < result.length(); i++) {
                String one = String.valueOf(result.charAt(i));
                chars.add(one);
                keys.add(one.toLowerCase(Locale.ROOT));
            }
            StringBuilder out = new StringBuilder();
            for (String one : cutRuns(chars, keys, CHAR_PERIODS, CHAR_REPEATS, PHRASE_REPEATS)) out.append(one);
            result = out.toString();
        }
        return result;
    }

    private static boolean hasOtherScript(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && LanguagePolicy.scriptOf(c) == LanguagePolicy.Script.OTHER) return true;
        }
        return false;
    }

    /** Letters and digits in the text: what is counted to tell how much of a window is loop. */
    private static int size(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) count++;
        }
        return count;
    }

    /**
     * Walks the items; where a piece of {@code period} items repeats often enough (a single item: {@code singleRepeats},
     * a longer piece: {@code phraseRepeats}) the piece is kept twice and the rest of the run dropped.
     */
    private static List<String> cutRuns(List<String> items, List<String> keys, int maxPeriod, int singleRepeats,
                                        int phraseRepeats) {
        List<String> kept = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            int skipTo = -1;
            for (int period = 1; period <= maxPeriod && skipTo < 0; period++) {
                int repeats = 1;
                while (same(keys, i, i + repeats * period, period)) repeats++;
                if (repeats >= (period == 1 ? singleRepeats : phraseRepeats)) {
                    for (int r = 0; r < 2; r++) {
                        for (int k = 0; k < period; k++) kept.add(items.get(i + r * period + k));
                    }
                    skipTo = i + repeats * period;
                }
            }
            if (skipTo >= 0) {
                i = skipTo;
            } else {
                kept.add(items.get(i));
                i++;
            }
        }
        return kept;
    }

    private static boolean same(List<String> keys, int first, int second, int length) {
        if (second + length > keys.size()) return false;
        for (int k = 0; k < length; k++) {
            String key = keys.get(first + k);
            if (key.isEmpty() || !key.equals(keys.get(second + k))) return false;
        }
        return true;
    }
}
