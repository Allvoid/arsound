package app.revanced.extension.soundcloud.local;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.soundcloud.shared.SearchText;

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
        BITRATE,
        /** The longest first, as on the desktop. */
        DURATION
    }

    /** One track of the playlist as the search sees it; {@link #known} is false while its tags are not read yet. */
    static final class Item {
        final Object urn;
        final boolean known;
        final String title, artist, album, fileName;
        final int year, track, bitRate;
        final boolean lossless;
        /** The length of the file in milliseconds, 0 when it is not known. */
        final long durationMs;
        /**
         * The year the album order tells albums of one name apart by, set by {@link #apply} (see {@link #albumYears}):
         * 0 for an album that has one year at most, so its tracks stay together.
         */
        int albumYear;

        Item(Object urn, boolean known, String title, String artist, String album, String fileName,
             int year, int track, int bitRate, boolean lossless) {
            this(urn, known, title, artist, album, fileName, year, track, bitRate, lossless, 0);
        }

        Item(Object urn, boolean known, String title, String artist, String album, String fileName,
             int year, int track, int bitRate, boolean lossless, long durationMs) {
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
            this.durationMs = durationMs;
        }

        /** A track of the playlist that is not a file (a SoundCloud track): only its title is known, if at all. */
        static Item other(Object urn, String title) {
            // Its title is all a SoundCloud track has: with one it sorts among the files by title.
            return new Item(urn, title != null && !title.isEmpty(), title, "", "", "", 0, 0, 0, false);
        }
    }

    private ImportedQuery() {
    }

    /** Lower case, "ё" as "е", runs of anything but letters and digits as one space ({@link SearchText}). */
    static String normalize(String text) {
        return SearchText.normalize(text);
    }

    /** The words of a query; none for an empty one. */
    static String[] words(String query) {
        return SearchText.words(query);
    }

    /** Every word is somewhere in the title, the artist, the album or the file name. */
    static boolean matches(Item item, String[] words, boolean losslessOnly) {
        if (losslessOnly && !item.lossless) return false;
        if (words.length == 0) return true;
        String haystack = normalize(item.title + " " + item.artist + " " + item.album + " " + item.fileName);
        return SearchText.containsAll(haystack, words);
    }

    /** The urns to show, in the order to show them. The order of {@link Sort#ADDED} and of ties is the given one. */
    static List<Object> apply(List<Item> items, String query, boolean losslessOnly, Sort sort) {
        return shown(items, query, losslessOnly, sort).urns;
    }

    /** What the playlist shows for a search: the urns in order, and the albums they make (0 unless in album order). */
    static final class Shown {
        final List<Object> urns;
        final int albums;

        Shown(List<Object> urns, int albums) {
            this.urns = urns;
            this.albums = albums;
        }
    }

    /** The same as {@link #apply}, with the album count of the same sorted list, so it is not sorted a second time. */
    static Shown shown(List<Item> items, String query, boolean losslessOnly, Sort sort) {
        String[] words = words(query);
        List<Item> kept = new ArrayList<>();
        for (Item item : items) if (matches(item, words, losslessOnly)) kept.add(item);
        // A stable sort: tracks that compare equal keep the playlist's order.
        if (sort == Sort.ALBUM) albumYears(kept);
        if (sort != Sort.ADDED) kept.sort(comparator(sort));
        List<Object> result = new ArrayList<>(kept.size());
        for (Item item : kept) result.add(item.urn);
        return new Shown(result, sort == Sort.ALBUM ? albumCount(kept) : 0);
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
                // Two albums of one name are told apart by their years; the tracks of an album go by their numbers.
                return knownFirst.thenComparing(album).thenComparing((a, b) -> numberUp(a.albumYear, b.albumYear))
                        .thenComparing(track).thenComparing(title);
            case YEAR:
                return knownFirst.thenComparing((a, b) -> numberDown(a.year, b.year)).thenComparing(album)
                        .thenComparing(track);
            case BITRATE:
                return knownFirst.thenComparing((a, b) -> numberDown(a.bitRate, b.bitRate));
            case DURATION:
                return knownFirst.thenComparing((a, b) -> longDown(a.durationMs, b.durationMs));
            default:
                return (a, b) -> 0;
        }
    }

    /**
     * Sets {@link Item#albumYear} of the given tracks. Albums of one name ("Greatest Hits" of two bands) are told apart
     * by their years, but an album whose tracks give one year at most (or a compilation of many artists with one
     * year) stays together, and a track without a year of an album that has several goes after them. The desktop
     * compares the years of two tracks only when both are known; this decides it per album, so the order is
     * consistent (a sort that is not may fail in Java).
     */
    static void albumYears(List<Item> items) {
        java.util.Map<String, java.util.Set<Integer>> years = new java.util.HashMap<>();
        for (Item item : items) {
            java.util.Set<Integer> set = years.get(albumKey(item.album));
            if (set == null) years.put(albumKey(item.album), set = new java.util.HashSet<>());
            if (item.year > 0) set.add(item.year);
        }
        for (Item item : items) {
            // The files without an album are one group whatever their years.
            item.albumYear = !item.album.isEmpty() && years.get(albumKey(item.album)).size() > 1 ? item.year : 0;
        }
    }

    private static String albumKey(String album) {
        return normalize(album);
    }

    /** One album of the list in album order: what a header over its tracks would say. */
    static final class AlbumGroup {
        final String album;
        /** The artist when all its tracks share one, else empty (a collection). */
        final String artist;
        /** The year of the album when its tracks give one, else 0. */
        final int year;
        final int count;

        AlbumGroup(String album, String artist, int year, int count) {
            this.album = album;
            this.artist = artist;
            this.year = year;
            this.count = count;
        }
    }

    /**
     * The albums of tracks that are already in album order ({@link Sort#ALBUM}), in that order: a new one starts where
     * the album name or the album's year changes. Files without an album are one group with an empty name.
     */
    static List<AlbumGroup> albumGroups(List<Item> inAlbumOrder) {
        List<AlbumGroup> groups = new ArrayList<>();
        String album = null;
        int albumYear = 0, year = 0, count = 0;
        String artist = "";
        for (Item item : inAlbumOrder) {
            boolean other = album == null || !albumKey(album).equals(albumKey(item.album)) || albumYear != item.albumYear;
            if (other) {
                if (album != null) groups.add(new AlbumGroup(album, artist, year, count));
                album = item.album;
                albumYear = item.albumYear;
                artist = item.artist;
                year = item.year;
                count = 0;
            }
            count++;
            if (!artist.equals(item.artist)) artist = "";
            if (year == 0) year = item.year;
        }
        if (album != null) groups.add(new AlbumGroup(album, artist, year, count));
        return groups;
    }

    /**
     * The number of albums of tracks already in album order, as the playlist counts them. Files without an album are
     * not one. A track without a year of an album that has years is part of that album's last group, not one more.
     */
    static int albumCount(List<Item> inAlbumOrder) {
        int count = 0;
        String album = null;
        int albumYear = 0;
        for (Item item : inAlbumOrder) {
            boolean other = album == null || !albumKey(album).equals(albumKey(item.album)) || albumYear != item.albumYear;
            if (!other) continue;
            boolean sameAlbum = album != null && albumKey(album).equals(albumKey(item.album));
            if (!item.album.isEmpty() && !(sameAlbum && item.albumYear == 0)) count++;
            album = item.album;
            albumYear = item.albumYear;
        }
        return count;
    }

    /** Large first, 0 (not known) last. */
    private static int longDown(long a, long b) {
        if ((a <= 0) != (b <= 0)) return a <= 0 ? 1 : -1;
        return Long.compare(b, a);
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
