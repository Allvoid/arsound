package app.revanced.extension.soundcloud.lyrics;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

    /** Words a record's title may add to the song's name without being another song: the cut, the format. */
    private static final Set<String> TAG_WORDS = new HashSet<>(Arrays.asList(
            "radio", "edit", "remaster", "remastered", "remastering", "mono", "stereo", "version", "single", "album",
            "deluxe", "bonus", "original", "extended", "explicit", "clean", "official", "audio", "video", "lyrics",
            "lyric", "hd", "hq", "live", "acoustic", "demo", "mix", "remix",
            "версия", "ремастер", "ремастеринг", "радио", "оригинал", "текст"));
    /** After one of these the record names guests, whoever they are. */
    private static final Set<String> GUEST_WORDS = new HashSet<>(Arrays.asList("feat", "ft", "featuring"));

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
        double contained = (a.contains(b) || b.contains(a)) && Math.min(a.length(), b.length()) >= 4 ? 0.9 : 0;
        return Math.max(sharedWords(a, b), contained);
    }

    /** The share of the words two normalized texts have in common. */
    private static double sharedWords(String a, String b) {
        Set<String> wordsA = words(a);
        Set<String> wordsB = words(b);
        Set<String> common = new HashSet<>(wordsA);
        common.retainAll(wordsB);
        Set<String> all = new HashSet<>(wordsA);
        all.addAll(wordsB);
        return all.isEmpty() ? 0 : (double) common.size() / all.size();
    }

    /**
     * How alike the title wanted and the title of a record are, 0 to 1. Like {@link #similarity}, but a title that
     * contains the other counts as the same song (0.9) only when all it adds is the artist's name, a guest, a tag of
     * the cut ("Radio Edit", "Remastered 2011") or a number. "Karma" is not "Karma Police" and "Stay" is not "Stay With
     * Me": words of another song are worse than none.
     *
     * @param artists Names the extra words may be (the artist as the track, the uploader and the record name him).
     */
    static double titleSimilarity(String wanted, String record, Collection<String> artists) {
        String a = normalize(wanted);
        String b = normalize(record);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        boolean same = shorter.length() >= 4 && addsOnlyTags(shorter, longer, artists);
        return Math.max(sharedWords(a, b), same ? 0.9 : 0);
    }

    /** Whether the longer text is the shorter one, word for word, with only an artist, tags or a guest around it. */
    private static boolean addsOnlyTags(String shorter, String longer, Collection<String> artists) {
        List<String> inner = Arrays.asList(shorter.split(" "));
        List<String> outer = Arrays.asList(longer.split(" "));
        int at = Collections.indexOfSubList(outer, inner);
        if (at < 0) return false;
        Set<String> names = new HashSet<>();
        for (String artist : artists) names.addAll(words(normalize(artist)));
        int end = at + inner.size();
        for (int i = 0; i < outer.size(); i++) {
            if (i >= at && i < end) continue;
            String word = outer.get(i);
            if (i >= end && GUEST_WORDS.contains(word)) return true;
            if (names.contains(word) || TAG_WORDS.contains(word) || isNumber(word)) continue;
            return false;
        }
        return true;
    }

    private static boolean isNumber(String word) {
        for (int i = 0; i < word.length(); i++) if (!Character.isDigit(word.charAt(i))) return false;
        return true;
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
