package app.revanced.extension.soundcloud.recommendations;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What makes two titles the same song: the title with brackets, "feat." tails, noise words and punctuation taken out,
 * and the markers of another version (remix, slowed, 1.25x) kept apart. Pure text work, no Android.
 */
final class SongKeys {
    private static final Pattern BRACKETS = Pattern.compile("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");
    private static final Pattern FEATURING = Pattern.compile("\\b(feat|ft|prod|featuring)\\b\\.?.*$");
    private static final Pattern NOISE = Pattern.compile("\\b(free download|free dl|official audio|official video|lyrics|audio|hq|hd|clean|explicit)\\b");
    private static final Pattern EDIT_MARKERS = Pattern.compile("\\b(slowed|slow|reverb|sped up|speed up|speed|nightcore|remix|edit|cover|live|instrumental|acapella|bass boosted|pitched|8d)\\b");
    /** A playback speed written as a number: "0.9x", "x1.25", "1,1 speed". */
    private static final Pattern SPEED = Pattern.compile("(\\b\\d(?:[.,]\\d+)?\\s*x\\b|\\bx\\s*\\d(?:[.,]\\d+)?\\b|\\b\\d(?:[.,]\\d+)?\\s*(?=speed\\b))");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SongKeys() {
    }

    /** @param mergeEdited Slowed, sped up and remixed versions get the key of the original. */
    static String key(String title, boolean mergeEdited) {
        if (title == null) return "";
        String value = title.toLowerCase(Locale.ROOT);
        // Markers of a different version are kept apart from the brackets they are usually written in.
        StringBuilder markers = new StringBuilder();
        if (!mergeEdited) {
            Matcher matcher = EDIT_MARKERS.matcher(value);
            while (matcher.find()) markers.append(' ').append(matcher.group(1));
            if (SPEED.matcher(value).find()) markers.append(" speed");
        }
        value = SPEED.matcher(value).replaceAll(" ");
        value = BRACKETS.matcher(value).replaceAll(" ");
        value = FEATURING.matcher(value).replaceAll(" ");
        value = NOISE.matcher(value).replaceAll(" ");
        if (!mergeEdited) value = EDIT_MARKERS.matcher(value).replaceAll(" ");
        // "Artist - Title" uploads: the part after the dash is the title in most re-uploads.
        int dash = value.lastIndexOf(" - ");
        if (dash > 0 && dash < value.length() - 3) value = value.substring(dash + 3);
        value = NON_WORD.matcher(value).replaceAll(" ").trim();
        return value + markers;
    }

    /** Slowed, sped up, remixed and similar: another speed changes the duration. */
    static boolean isEdited(String title) {
        String value = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return EDIT_MARKERS.matcher(value).find() || SPEED.matcher(value).find();
    }
}
