package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the tags uploaders add off a title and an uploader's name: [Official Video], (prod. X), (feat. Y),
 * (Slowed + Reverb), "ArtistVEVO", "Artist - Topic". Pure text work, so it runs in the JVM tests.
 */
final class TitleCleaner {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** (...) [...] {...} and the Japanese brackets. */
    private static final Pattern BRACKET = Pattern.compile(
            "[(\\[{【「『][^)\\]}】」』]*[)\\]}】」』]");
    /** Words of a tag that says nothing about the song. */
    private static final Pattern JUNK = Pattern.compile("\\b(?:official|video|audio|lyrics?|lyric video|visuali[sz]er"
            + "|music video|mv|hd|hq|4k|remaster(?:ed)?(?:\\s*\\d{4})?|prod\\.?(?:\\s+by)?|produced by|free download"
            + "|out now|premiere|snippet|clip|explicit|clean|full song|full version|new song|new music|video oficial"
            + "|audio oficial|клип|премьера|текст|karaoke)\\b", FLAGS);
    /** Words of a tag that name another version of the song. */
    private static final Pattern VERSION = Pattern.compile("\\b(?:remix|mix|edit|vip|sped up|speed up|slowed|reverb"
            + "|nightcore|live|acoustic|instrumental|cover|bootleg|flip|version)\\b", FLAGS);
    /** Versions that change the speed, so the timings of the original do not fit. */
    private static final Pattern RETIMED = Pattern.compile("\\b(?:sped up|speed up|slowed|nightcore|8d)\\b", FLAGS);
    private static final Pattern FEAT_WORD = Pattern.compile("\\b(?:feat|ft|featuring)\\b", FLAGS);
    /** "feat. A & B" outside brackets, up to a closing bracket, a spaced dash or the end. */
    private static final Pattern FEAT = Pattern.compile("\\s*[(\\[]?\\s*\\b(?:feat\\.?|ft\\.?|featuring|w/)\\s+([^)\\]]*?)"
            + "\\s*(?:[)\\]]|(?=\\s[-–—|]\\s)|$)", FLAGS);
    /** The guests inside a tag: "(feat. A &amp; B)". */
    private static final Pattern FEAT_INSIDE = Pattern.compile("\\b(?:feat\\.?|ft\\.?|featuring)\\s+(.*)$", FLAGS);
    private static final Pattern PRODUCER = Pattern.compile("\\s+(?:prod\\.?\\s+by|produced\\s+by|prod\\.)\\s+.*$", FLAGS);
    private static final Pattern NAME_SPLIT = Pattern.compile("\\s*(?:,|&|\\band\\b|\\bи\\b|\\bx\\b)\\s*", FLAGS);
    private static final Pattern TOPIC = Pattern.compile("\\s*[-–]\\s*topic$", FLAGS);
    private static final Pattern UPLOADER_WORDS = Pattern.compile("\\b(?:vevo|official|records?|music|archive|channel|tv|hd"
            + "|network|entertainment|label|lyrics?)\\b", FLAGS);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TitleCleaner() {
    }

    /** A title with its tags taken off, and what the tags told. */
    static final class Cleaned {
        final String title;
        /** Guests named in the tags. */
        final List<String> featured;
        /** A tag names another version (remix, live, slowed): the timings of the original may not fit. */
        final boolean versionTagged;

        Cleaned(String title, List<String> featured, boolean versionTagged) {
            this.title = title;
            this.featured = featured;
            this.versionTagged = versionTagged;
        }
    }

    /**
     * The title without the tags uploaders add: [Official Video], (prod. X), (feat. Y), (Slowed + Reverb).
     *
     * @param dropAllBrackets Also drop brackets of unknown content ("[free for profit]"), not only the known tags.
     */
    static Cleaned clean(String title, boolean dropAllBrackets) {
        List<String> featured = new ArrayList<>();
        boolean versionTagged = false;
        Matcher matcher = BRACKET.matcher(title);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String whole = matcher.group();
            String inner = whole.substring(1, whole.length() - 1);
            String replacement;
            if (JUNK.matcher(inner).find() || FEAT_WORD.matcher(inner).find()) {
                Matcher guests = FEAT_INSIDE.matcher(inner);
                if (guests.find()) addGuests(featured, guests.group(1));
                replacement = " ";
            } else if (VERSION.matcher(inner).find()) {
                versionTagged = true;
                replacement = " ";
            } else {
                replacement = dropAllBrackets ? " " : whole;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        String text = result.toString();
        Matcher guests = FEAT.matcher(text);
        if (guests.find()) {
            addGuests(featured, guests.group(1));
            text = FEAT.matcher(text).replaceAll(" ");
        }
        text = PRODUCER.matcher(text).replaceAll("");
        return new Cleaned(trimEdges(SPACES.matcher(text).replaceAll(" ")), featured, versionTagged);
    }

    /** A title without the tags uploaders add; for comparing a record's name with the title. */
    static String withoutTags(String title) {
        return clean(title == null ? "" : title, true).title;
    }

    /** Whether the title names a version that changes the speed (sped up, slowed, nightcore). */
    static boolean isRetimed(String title) {
        return RETIMED.matcher(title).find();
    }

    private static void addGuests(List<String> featured, String names) {
        for (String name : NAME_SPLIT.split(names)) {
            String guest = name.trim();
            if (!guest.isEmpty()) featured.add(guest);
        }
    }

    /** The uploader's name without the words a channel adds to it ("Artist - Topic", "ArtistVEVO", "Artist Official"). */
    static String cleanUploader(String uploader) {
        String text = uploader.replace('_', ' ');
        text = TOPIC.matcher(text).replaceAll("");
        text = UPLOADER_WORDS.matcher(text).replaceAll(" ");
        text = trimEdges(SPACES.matcher(text).replaceAll(" "));
        return text.isEmpty() ? uploader.trim() : text;
    }

    private static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && " -–—|•_,".indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && " -–—|•_,".indexOf(text.charAt(end - 1)) >= 0) end--;
        return text.substring(start, end);
    }
}
