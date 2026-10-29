package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One word a model heard in a window, with when it was sung, in milliseconds from the start of the window. GigaAM
 * (a CTC model) tells the moment of every letter it writes, 40 ms apart; the words and their pauses are read from
 * that. Whisper tells no moments, so it has no words of this kind.
 * <p>
 * Made by {@link GigaAmRunner}, cut into lines by {@link PhraseLines}.
 */
final class HeardWord {
    final String text;
    /** When the first letter was heard. */
    final long startMs;
    /** When the last letter was heard. */
    final long endMs;

    HeardWord(String text, long startMs, long endMs) {
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    /**
     * The words of a recognition result: the tokens are letters (GigaAM) or pieces of words, a blank token or a
     * piece starting with "▁" begins a new word.
     *
     * @return The words in order; empty when the result has no moments (the model does not give them).
     */
    static List<HeardWord> fromTokens(String[] tokens, float[] seconds) {
        if (tokens == null || seconds == null || tokens.length == 0 || tokens.length != seconds.length) {
            return Collections.emptyList();
        }
        List<HeardWord> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        long start = 0;
        long end = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            long at = Math.round(seconds[i] * 1000.0);
            boolean blank = token.trim().isEmpty();
            boolean begins = token.startsWith("▁");
            if ((blank || begins) && word.length() > 0) {
                words.add(new HeardWord(word.toString(), start, end));
                word.setLength(0);
            }
            if (blank) continue;
            String letters = begins ? token.substring(1) : token;
            if (letters.isEmpty()) continue;
            if (word.length() == 0) start = at;
            word.append(letters);
            end = at;
        }
        if (word.length() > 0) words.add(new HeardWord(word.toString(), start, end));
        return words;
    }

    /**
     * The heard words that are left in the cleaned text of the window ({@link TextCleaner#inspect} drops loops and
     * made-up phrases, it never adds a word).
     *
     * @return The words in the order of the text, or null when the text is not made of them (then the lines are
     * timed without the moments).
     */
    static List<HeardWord> keptIn(List<HeardWord> heard, String cleanedText) {
        String plain = TextCleaner.plain(cleanedText);
        if (plain.isEmpty()) return null;
        List<HeardWord> kept = new ArrayList<>();
        int next = 0;
        for (String wanted : plain.split(" ")) {
            while (next < heard.size() && !TextCleaner.plain(heard.get(next).text).equals(wanted)) next++;
            if (next == heard.size()) return null;
            kept.add(heard.get(next++));
        }
        return kept;
    }

    @Override
    public String toString() {
        return text + "@" + startMs;
    }
}
