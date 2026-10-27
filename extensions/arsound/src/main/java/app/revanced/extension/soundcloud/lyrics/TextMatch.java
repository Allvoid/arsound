package app.revanced.extension.soundcloud.lyrics;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Comparison of titles and names the way people write them: case, accents, punctuation and "ё" against "е" do not
 * matter. Used to tell whether a LRCLIB record is the track that plays.
 */
final class TextMatch {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("[’'`´]");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TextMatch() {
    }

    /** Lower case without accents, apostrophes and punctuation, single spaces. */
    static String normalize(String text) {
        if (text == null) return "";
        String result = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        result = MARKS.matcher(result).replaceAll("");
        result = APOSTROPHES.matcher(result).replaceAll("");
        result = NOT_WORD.matcher(result).replaceAll(" ");
        return SPACES.matcher(result).replaceAll(" ").trim();
    }

    /**
     * How alike two names are, 0 to 1: the share of the words they have in common, or 0.9 when one contains the
     * other (a title and the same title with an artist in front of it).
     */
    static double similarity(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        Set<String> wordsA = words(a);
        Set<String> wordsB = words(b);
        Set<String> common = new HashSet<>(wordsA);
        common.retainAll(wordsB);
        Set<String> all = new HashSet<>(wordsA);
        all.addAll(wordsB);
        double shared = all.isEmpty() ? 0 : (double) common.size() / all.size();
        double contained = (a.contains(b) || b.contains(a)) && Math.min(a.length(), b.length()) >= 4 ? 0.9 : 0;
        return Math.max(shared, contained);
    }

    /**
     * Whether one text is written in Cyrillic and the other is not. Then the way one is spelled says nothing about the
     * other: the same artist is "Кино" in one place and "Kino" in the next.
     */
    static boolean differentAlphabets(String first, String second) {
        int a = alphabet(first);
        int b = alphabet(second);
        return a != 0 && b != 0 && a != b;
    }

    /** 1 for a text with Cyrillic letters, 2 for other letters only, 0 for no letters at all. */
    private static int alphabet(String text) {
        if (text == null) return 0;
        boolean letters = false;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            if (!Character.isLetter(code)) continue;
            if (Character.UnicodeScript.of(code) == Character.UnicodeScript.CYRILLIC) return 1;
            letters = true;
        }
        return letters ? 2 : 0;
    }

    private static Set<String> words(String normalized) {
        Set<String> result = new HashSet<>();
        for (String word : normalized.split(" ")) if (!word.isEmpty()) result.add(word);
        return result;
    }

    /** A short stable hash of a text, for telling whether a stored answer belongs to the same track. */
    static String hash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }
}
