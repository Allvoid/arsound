package app.revanced.extension.soundcloud.settings.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Splits texts into {@link Token}s and normalizes them. No Android here, so the search is tested on the JVM. */
public final class Text {
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "not", "and", "in", "on", "with", "by", "for", "how", "what", "the", "a", "of", "to", "or",
            "не", "и", "в", "на", "с", "по", "для", "как", "что", "к", "у", "о", "из", "от"));

    private Text() {
    }

    /**
     * Lower case, "ё" as "е". One character stays one character, so a position found in the result is also the
     * position in the original text.
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            result.append(c == 'ё' ? 'е' : c);
        }
        return result.toString();
    }

    /** The words of a text. A "+" right after a digit belongs to the word, so "18+" is one word. */
    public static List<Token> tokens(String text) {
        String norm = normalize(text);
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < norm.length()) {
            if (!Character.isLetterOrDigit(norm.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < norm.length() && Character.isLetterOrDigit(norm.charAt(i))) i++;
            if (i < norm.length() && norm.charAt(i) == '+' && Character.isDigit(norm.charAt(i - 1))) i++;
            String word = norm.substring(start, i);
            tokens.add(new Token(start, i, word, Stemmer.stem(word)));
        }
        return tokens;
    }

    /** Small words that say nothing about what is searched for. */
    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }
}
