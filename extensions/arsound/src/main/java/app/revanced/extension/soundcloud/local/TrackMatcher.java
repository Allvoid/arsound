package app.revanced.extension.soundcloud.local;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deciding whether a search result is the track that was asked for: by the same artist, with the wanted title,
 * and not a snippet or another version of it. Pure text rules; no network and no Android.
 */
final class TrackMatcher {
    private static final String[] VARIANTS = {"speed up", "sped up", "speedup", "slowed", "reverb", "remix",
            "nightcore", "cover", "karaoke", "instrumental", "минус", "ускор", "замедл", "ремикс", "кавер",
            "censored", "clean version", "radio edit", "цензур"};

    private static final String[] SNIPPETS = {"snippet", "сниппет", "отрывок", "preview", "teaser", "тизер"};

    /** A speed multiplier such as "1.10x" or "0,8x". */
    private static final String SPEED_MULTIPLIER = ".*\\b\\d+[.,]\\d+\\s*x\\b.*";
    /** Words in brackets at the end of a title that name a version of the song, not a guest. */
    private static final Pattern VERSION = Pattern.compile(
            "(?iu).*(version|mix|edit|slow|sped|speed|acoustic|live|demo|instrumental|версия|акуст|живое|замедл|ускор).*");
    private static final Pattern NOTE = Pattern.compile("\\(([^)]*)\\)\\s*$");
    /** Words of such a note that the found title does not have to repeat. */
    private static final Set<String> VERSION_FILLER = new HashSet<>(Arrays.asList("version", "ver", "the", "версия"));
    /** A title shorter than this (after {@link #normalize}) must be the whole found title. */
    private static final int SHORT_TITLE = 8;

    private TrackMatcher() {
    }

    /** Lower case letters and digits only, with "ё" as "е": titles are compared without spaces, marks and case. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Whether the artist is named by the uploader of the result or in its title. */
    static boolean isByArtist(String uploader, String foundTitle, String artist) {
        return normalize(uploader).contains(normalize(artist)) || normalize(foundTitle).contains(normalize(artist));
    }

    /**
     * The result is the wanted track in full: by the artist, holding the wanted title, and not another version.
     * A snippet is a separate question, see {@link #isSnippet}.
     */
    static boolean isWantedTrack(String uploader, String foundTitle, String artist, String wantedTitle) {
        return isByArtist(uploader, foundTitle, artist)
                && holdsTitle(stripArtist(foundTitle, artist), wantedTitle)
                && !isVariant(foundTitle, wantedTitle);
    }

    /** A short piece of a track, unless the wanted title names it. */
    static boolean isSnippet(String found, String wanted) {
        return namesAnyUnlessWanted(SNIPPETS, found, wanted);
    }

    /** A sped-up, slowed, remixed, covered or censored version, unless the wanted title names it. */
    static boolean isVariant(String found, String wanted) {
        if (namesAnyUnlessWanted(VARIANTS, found, wanted)) return true;
        String lowerFound = found.toLowerCase(Locale.ROOT);
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        return lowerFound.matches(SPEED_MULTIPLIER) && !lowerWanted.matches(SPEED_MULTIPLIER);
    }

    private static boolean namesAnyUnlessWanted(String[] words, String found, String wanted) {
        String lowerFound = found.toLowerCase(Locale.ROOT);
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lowerFound.contains(word) && !lowerWanted.contains(word)) return true;
        }
        return false;
    }

    /** "CUPSIZE - title" and "title" name the same track. */
    static String stripArtist(String found, String artist) {
        String lower = found.toLowerCase(Locale.ROOT);
        String prefix = artist.toLowerCase(Locale.ROOT);
        if (lower.startsWith(prefix)) return found.substring(prefix.length()).replaceFirst("^\\s*[-–—:]\\s*", "");
        return found;
    }

    /**
     * "В окно с тобой (Slow Version)" is another recording than "В окно с тобой": when the wanted title
     * names a version, the found one must name it too.
     */
    static boolean namesVersion(String found, String wanted) {
        Matcher note = NOTE.matcher(wanted);
        if (!note.find() || !VERSION.matcher(note.group(1)).matches()) return true;
        String foundText = normalize(found);
        for (String word : note.group(1).split("\\s+")) {
            String part = normalize(word);
            if (!part.isEmpty() && !VERSION_FILLER.contains(part) && !foundText.contains(part)) return false;
        }
        return true;
    }

    /** The wanted title without a note in brackets at the end, as it is compared. */
    static String plainTitle(String wanted) {
        return normalize(wanted.replaceAll("\\s*\\([^)]*\\)\\s*$", ""));
    }

    /** The found title holds the whole wanted one: "вата" must not stand for "сахарная вата". */
    static boolean holdsTitle(String found, String wanted) {
        String b = plainTitle(wanted);
        if (b.isEmpty() || !namesVersion(found, wanted)) return false;
        // A short title such as "17" must be the whole title, or "17 ножевых" would pass for it.
        if (b.length() < SHORT_TITLE) {
            // "CUPSIZE - 17" and "no drama - cupsize, эмпи": the artist may be on either side of a dash.
            for (String part : found.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ").split("\\s[-—–]\\s")) {
                if (normalize(part).equals(b)) return true;
            }
            return false;
        }
        return normalize(found).contains(b);
    }

    /** A file imported earlier is the wanted track: its tag title holds the whole wanted title. */
    static boolean isImportedMatch(String tagTitle, String wantedTitle) {
        String wanted = plainTitle(wantedTitle);
        return !wanted.isEmpty() && normalize(tagTitle).contains(wanted) && namesVersion(tagTitle, wantedTitle);
    }
}
