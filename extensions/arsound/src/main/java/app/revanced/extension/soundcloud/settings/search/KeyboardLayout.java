package app.revanced.extension.soundcloud.settings.search;

/** Turns text typed on the wrong keyboard layout back: "gfvznm" is "память" typed in English. */
public final class KeyboardLayout {
    private static final String LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
    private static final String CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбю";

    private KeyboardLayout() {
    }

    /** The word as if it was typed on the Russian layout; characters outside the map stay as they are. */
    public static String toCyrillic(String text) {
        return map(text, LATIN, CYRILLIC);
    }

    /** The word as if it was typed on the English layout; characters outside the map stay as they are. */
    public static String toLatin(String text) {
        return map(text, CYRILLIC, LATIN);
    }

    private static String map(String text, String from, String to) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int at = from.indexOf(c);
            result.append(at >= 0 ? to.charAt(at) : c);
        }
        return result.toString();
    }

    /** True if the word has only Latin letters and at least one. */
    public static boolean isLatin(String word) {
        return onlyFrom(word, 'a', 'z');
    }

    /** True if the word has only Cyrillic letters and at least one. */
    public static boolean isCyrillic(String word) {
        return onlyFrom(word, 'а', 'я');
    }

    /**
     * True if the word looks like Russian typed on the English layout: Latin letters (at least one) and, because six
     * Russian letters (б ж х э ю ъ) sit on the punctuation keys, the keys {@code [ ] ; ' , .} as well.
     */
    public static boolean isLatinTyped(String word) {
        boolean letter = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= 'a' && c <= 'z') letter = true;
            else if (LATIN.indexOf(c) < 0) return false;
        }
        return letter;
    }

    /** True if a punctuation key stands before the last letter; one only at the end may be a plain full stop or comma. */
    private static boolean hasInnerPunctuationKey(String chunk) {
        int end = chunk.length();
        while (end > 0 && (chunk.charAt(end - 1) < 'a' || chunk.charAt(end - 1) > 'z')) end--;
        for (int i = 0; i < end; i++) {
            char c = chunk.charAt(i);
            if (c < 'a' || c > 'z') return true;
        }
        return false;
    }

    /**
     * The query with every word that looks like wrong-layout typing and has a punctuation key before its last letter
     * ({@code [hfybkbot}, {@code ,fnfhtz}, {@code j,jb}) read on the Russian layout; null if no word needed it. Words
     * without such a key are left to the search itself, which sees them as plain Latin words.
     */
    public static String punctuationKeysAsLetters(String query) {
        String[] chunks = Text.normalize(query).trim().split("\\s+");
        boolean changed = false;
        StringBuilder result = new StringBuilder();
        for (String chunk : chunks) {
            if (result.length() > 0) result.append(' ');
            if (isLatinTyped(chunk) && hasInnerPunctuationKey(chunk)) {
                result.append(toCyrillic(chunk));
                changed = true;
            } else {
                result.append(chunk);
            }
        }
        return changed ? result.toString() : null;
    }

    private static boolean onlyFrom(String word, char low, char high) {
        if (word.isEmpty()) return false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < low || c > high) return false;
        }
        return true;
    }
}
