package app.revanced.extension.soundcloud.shared;

import java.util.Locale;

/**
 * Text as Arsound's own searches compare it: lower case, "ё" as "е", and every run of characters that are not letters
 * or digits as one space. A query matches when each of its words is somewhere in the text. No Android here.
 */
public final class SearchText {
    private SearchText() {
    }

    public static String normalize(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder result = new StringBuilder(lower.length());
        boolean space = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                result.append(c);
                space = false;
            } else if (!space) {
                result.append(' ');
                space = true;
            }
        }
        int end = result.length();
        if (end > 0 && result.charAt(end - 1) == ' ') result.setLength(end - 1);
        return result.toString();
    }

    /** The words of a query; none for an empty one. */
    public static String[] words(String query) {
        String normal = normalize(query);
        return normal.isEmpty() ? new String[0] : normal.split(" ");
    }

    /** Whether every word is in the normalized text. No words: true. */
    public static boolean containsAll(String normalizedText, String[] words) {
        for (String word : words) if (!normalizedText.contains(word)) return false;
        return true;
    }
}
