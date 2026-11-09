package app.revanced.extension.soundcloud.local;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The search and the order of the "Imported" playlist, without Android: which tracks match the words typed, whether
 * only lossless files are shown, and the order they are shown in. {@link ImportedView} feeds it the tags kept by
 * {@link FileTags} and puts the result into the playlist.
 */
final class ImportedQuery {
    /** The orders of the playlist; {@link #ADDED} keeps the order it has (newest first, or the user's own). */
    enum Sort {
        ADDED,
        TITLE,
        ARTIST,
        ALBUM,
        YEAR,
        BITRATE
    }

    /** One track of the playlist as the search sees it; {@link #known} is false while its tags are not read yet. */
    static final class Item {
        final Object urn;
        final boolean known;
        final String title, artist, album, fileName;
        final int year, track, bitRate;
        final boolean lossless;

        Item(Object urn, boolean known, String title, String artist, String album, String fileName,
             int year, int track, int bitRate, boolean lossless) {
            this.urn = urn;
            this.known = known;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.fileName = fileName == null ? "" : fileName;
            this.year = year;
            this.track = track;
            this.bitRate = bitRate;
            this.lossless = lossless;
        }

        /** A track of the playlist that is not a file (a SoundCloud track): only its title is known, if at all. */
        static Item other(Object urn, String title) {
            return new Item(urn, false, title, "", "", "", 0, 0, 0, false);
        }
    }

    private ImportedQuery() {
    }

    /** Lower case, "ё" as "е", runs of anything but letters and digits as one space. */
    static String normalize(String text) {
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
    static String[] words(String query) {
        String normal = normalize(query);
        return normal.isEmpty() ? new String[0] : normal.split(" ");
    }

    /** Every word is somewhere in the title, the artist, the album or the file name. */
    static boolean matches(Item item, String[] words, boolean losslessOnly) {
        if (losslessOnly && !item.lossless) return false;
        if (words.length == 0) return true;
        String haystack = normalize(item.title + " " + item.artist + " " + item.album + " " + item.fileName);
        for (String word : words) if (!haystack.contains(word)) return false;
        return true;
    }

    /** The urns to show, in the order to show them. The order of {@link Sort#ADDED} and of ties is the given one. */
    static List<Object> apply(List<Item> items, String query, boolean losslessOnly, Sort sort) {
        String[] words = words(query);
        List<Item> kept = new ArrayList<>();
        for (Item item : items) if (matches(item, words, losslessOnly)) kept.add(item);
        // A stable sort: tracks that compare equal keep the playlist's order.
        if (sort != Sort.ADDED) kept.sort(comparator(sort));
        List<Object> result = new ArrayList<>(kept.size());
        for (Item item : kept) result.add(item.urn);
        return result;
    }

    static Comparator<Item> comparator(Sort sort) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
        // Tracks whose tags are not read yet, and empty values, go after the rest.
        Comparator<Item> knownFirst = (a, b) -> Boolean.compare(!a.known, !b.known);
        Comparator<Item> title = (a, b) -> text(collator, a.title, b.title);
        Comparator<Item> artist = (a, b) -> text(collator, a.artist, b.artist);
        Comparator<Item> album = (a, b) -> text(collator, a.album, b.album);
        Comparator<Item> track = (a, b) -> numberUp(a.track, b.track);
        switch (sort) {
            case TITLE:
                return knownFirst.thenComparing(title).thenComparing(artist);
            case ARTIST:
                return knownFirst.thenComparing(artist).thenComparing(album).thenComparing(track).thenComparing(title);
            case ALBUM:
                return knownFirst.thenComparing(album).thenComparing(track).thenComparing(title);
            case YEAR:
                return knownFirst.thenComparing((a, b) -> numberDown(a.year, b.year)).thenComparing(album)
                        .thenComparing(track);
            case BITRATE:
                return knownFirst.thenComparing((a, b) -> numberDown(a.bitRate, b.bitRate));
            default:
                return (a, b) -> 0;
        }
    }

    /** Alphabetical, an empty value last. */
    private static int text(Collator collator, String a, String b) {
        if (a.isEmpty() != b.isEmpty()) return a.isEmpty() ? 1 : -1;
        return collator.compare(a, b);
    }

    /** Small first, 0 (not known) last. */
    private static int numberUp(int a, int b) {
        if ((a <= 0) != (b <= 0)) return a <= 0 ? 1 : -1;
        return Integer.compare(a, b);
    }

    /** Large first, 0 (not known) last. */
    private static int numberDown(int a, int b) {
        if ((a <= 0) != (b <= 0)) return a <= 0 ? 1 : -1;
        return Integer.compare(b, a);
    }
}
