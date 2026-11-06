package app.revanced.extension.soundcloud.recommendations;

import java.util.Locale;

/** Artist and title names as My Wave, the statistics and the duplicate filter compare them. Pure text work, no Android. */
public final class SongNames {
    private SongNames() {
    }

    /** Artist and title of a SoundCloud upload: "Artist - Title" uploads name the artist in the title. */
    public static String[] artistAndTitle(String title, String uploader) {
        if (title == null) return null;
        for (String dash : new String[]{" - ", " – ", " — "}) {
            int index = title.indexOf(dash);
            if (index > 0 && index < title.length() - dash.length()) {
                return new String[]{title.substring(0, index).trim(), title.substring(index + dash.length()).trim()};
            }
        }
        if (uploader == null || uploader.isEmpty()) return null;
        return new String[]{uploader.trim(), title.trim()};
    }

    public static String artistKey(String artist) {
        String value = artist == null ? "" : artist.toLowerCase(Locale.ROOT);
        // The first of several artists: "A, B", "A & B", "A feat. B".
        String[] parts = value.split("\\s*(,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bи\\b)\\s*");
        String key = (parts.length == 0 ? "" : parts[0]).replaceAll("[^\\p{L}\\p{N}]+", "");
        // An artist called "X" or "&", or one that starts with a separator word ("X Ambassadors"), has nothing before it:
        // the whole name is the key then, instead of an empty one that matches every such artist.
        return key.isEmpty() ? value.replaceAll("[^\\p{L}\\p{N}]+", "") : key;
    }

    static String songKey(String artist, String title) {
        // Remixes and other speeds are other songs here, whatever the user chose for the lists: with them merged,
        // a remix found on Last.fm took the cached match of the original.
        return artistKey(artist) + "|" + SongKeys.key(title, false);
    }
}
