package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ImportedQueryTest {
    private static ImportedQuery.Item item(String urn, String title, String artist, String album, int year, int track,
                                           int bitRate, boolean lossless) {
        return new ImportedQuery.Item(urn, true, title, artist, album, title + ".mp3", year, track, bitRate, lossless);
    }

    private static final ImportedQuery.Item A = item("a", "Помойка", "шумные и угрожающие выходки", "Свалка", 2021, 2, 320_000, false);
    private static final ImportedQuery.Item B = item("b", "Ёлка", "Artist B", "Свалка", 2021, 1, 900_000, true);
    private static final ImportedQuery.Item C = item("c", "Anthem", "Artist C", "", 2019, 0, 256_000, false);
    private static final ImportedQuery.Item D = new ImportedQuery.Item("d", false, "unread", "", "", "unread.flac", 0, 0, 0, true);
    private static final List<ImportedQuery.Item> ALL = Arrays.asList(A, B, C, D);

    @Test
    public void normalizeFoldsCaseYoAndPunctuation() {
        assertEquals("елка в лесу", ImportedQuery.normalize("  Ёлка, в — ЛЕСУ!! "));
        assertEquals("", ImportedQuery.normalize(null));
        assertEquals(0, ImportedQuery.words("  ,, ").length);
    }

    @Test
    public void everyWordMustMatchSomewhere() {
        assertEquals(Arrays.asList("a"), ImportedQuery.apply(ALL, "свалка угрожающие", false, ImportedQuery.Sort.ADDED));
        assertEquals(Arrays.asList("a", "b"), ImportedQuery.apply(ALL, "Свалка", false, ImportedQuery.Sort.ADDED));
        assertEquals(Arrays.asList("b"), ImportedQuery.apply(ALL, "елка", false, ImportedQuery.Sort.ADDED));
        assertTrue(ImportedQuery.apply(ALL, "nothing", false, ImportedQuery.Sort.ADDED).isEmpty());
    }

    @Test
    public void theFileNameIsSearchedToo() {
        assertEquals(Arrays.asList("d"), ImportedQuery.apply(ALL, "unread flac", false, ImportedQuery.Sort.ADDED));
    }

    @Test
    public void losslessOnly() {
        assertEquals(Arrays.asList("b", "d"), ImportedQuery.apply(ALL, "", true, ImportedQuery.Sort.ADDED));
    }

    @Test
    public void addedKeepsTheGivenOrder() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), ImportedQuery.apply(ALL, "", false, ImportedQuery.Sort.ADDED));
    }

    @Test
    public void albumOrderGoesByTrackNumberAndEmptyAlbumsLast() {
        assertEquals(Arrays.asList("b", "a", "c", "d"), ImportedQuery.apply(ALL, "", false, ImportedQuery.Sort.ALBUM));
    }

    @Test
    public void yearAndBitRateGoDownUnknownLast() {
        assertEquals(Arrays.asList("b", "a", "c", "d"), ImportedQuery.apply(ALL, "", false, ImportedQuery.Sort.YEAR));
        assertEquals(Arrays.asList("b", "a", "c", "d"), ImportedQuery.apply(ALL, "", false, ImportedQuery.Sort.BITRATE));
    }

    @Test
    public void titleOrderIsAlphabetical() {
        List<Object> order = ImportedQuery.apply(Arrays.asList(A, C, B), "", false, ImportedQuery.Sort.TITLE);
        // Latin and Cyrillic: whatever the collator puts first, "Ёлка" comes before "Помойка".
        assertTrue(order.indexOf("b") < order.indexOf("a"));
        assertEquals(3, order.size());
    }

    @Test
    public void otherTracksAreFoundByTitleOnly() {
        ImportedQuery.Item other = ImportedQuery.Item.other("sc", "Cloud Song");
        assertTrue(ImportedQuery.matches(other, ImportedQuery.words("cloud"), false));
        assertFalse(ImportedQuery.matches(other, ImportedQuery.words("cloud"), true));
        assertFalse(ImportedQuery.matches(ImportedQuery.Item.other("sc", null), ImportedQuery.words("cloud"), false));
    }

    @Test
    public void tagsSurviveTheirRecord() {
        FileTags.Tags tags = new FileTags.Tags("Title", "Artist", "Album", 2019, 3, 320_000);
        FileTags.Tags back = FileTags.Tags.parse(tags.format());
        assertEquals("Album", back.album);
        assertEquals(2019, back.year);
        assertEquals(3, back.track);
        assertEquals(320_000, back.bitRate);
        assertNull(FileTags.Tags.parse("old|record"));
    }

    @Test
    public void yearsAndNumbersOfTags() {
        assertEquals(2019, FileTags.year("2019"));
        assertEquals(2019, FileTags.year("2019-05-01"));
        assertEquals(2019, FileTags.year("20190501T000000.000Z"));
        assertEquals(0, FileTags.year("19"));
        assertEquals(0, FileTags.year("abcd"));
        assertEquals(3, FileTags.leadingNumber("3/12"));
        assertEquals(0, FileTags.leadingNumber("/12"));
        assertEquals(0, FileTags.leadingNumber(null));
    }

    @Test
    public void aSoundCloudTrackWithATitleSortsAmongTheFiles() {
        ImportedQuery.Item cloud = ImportedQuery.Item.other("sc", "Bravo");
        ImportedQuery.Item a = item("a", "Alpha", "", "", 0, 0, 0, false);
        ImportedQuery.Item c = item("c", "Charlie", "", "", 0, 0, 0, false);
        assertEquals(Arrays.asList("a", "sc", "c"), ImportedQuery.apply(Arrays.asList(c, cloud, a), "", false, ImportedQuery.Sort.TITLE));
    }

    private static ImportedQuery.Item timed(String urn, String album, int year, int track, long durationMs) {
        return new ImportedQuery.Item(urn, true, urn, "Artist " + urn, album, urn + ".mp3", year, track, 0, false, durationMs);
    }

    @Test
    public void durationGoesFromTheLongestAndUnknownLengthsLast() {
        List<ImportedQuery.Item> items = Arrays.asList(
                timed("short", "", 0, 0, 60_000), timed("unknown", "", 0, 0, 0), timed("long", "", 0, 0, 400_000),
                timed("middle", "", 0, 0, 200_000));
        assertEquals(Arrays.asList("long", "middle", "short", "unknown"),
                ImportedQuery.apply(items, "", false, ImportedQuery.Sort.DURATION));
    }

    @Test
    public void equalLengthsKeepTheGivenOrder() {
        List<ImportedQuery.Item> items = Arrays.asList(timed("x", "", 0, 0, 5_000), timed("y", "", 0, 0, 5_000));
        assertEquals(Arrays.asList("x", "y"), ImportedQuery.apply(items, "", false, ImportedQuery.Sort.DURATION));
    }

    @Test
    public void twoAlbumsOfOneNameAreToldApartByTheirYears() {
        List<ImportedQuery.Item> items = Arrays.asList(
                timed("new2", "Greatest Hits", 2015, 2, 1), timed("old1", "Greatest Hits", 1999, 1, 1),
                timed("new1", "Greatest Hits", 2015, 1, 1), timed("old2", "Greatest Hits", 1999, 2, 1));
        assertEquals(Arrays.asList("old1", "old2", "new1", "new2"),
                ImportedQuery.apply(items, "", false, ImportedQuery.Sort.ALBUM));
    }

    @Test
    public void anAlbumWithOneYearStaysTogetherWhateverTheMissingYears() {
        List<ImportedQuery.Item> items = Arrays.asList(
                timed("t3", "Album", 2010, 3, 1), timed("t1", "Album", 0, 1, 1), timed("t2", "Album", 2010, 2, 1));
        assertEquals(Arrays.asList("t1", "t2", "t3"), ImportedQuery.apply(items, "", false, ImportedQuery.Sort.ALBUM));
    }

    @Test
    public void albumGroupsCountTracksAndKeepACommonArtistOnly() {
        List<ImportedQuery.Item> items = Arrays.asList(
                new ImportedQuery.Item("a1", true, "One", "Band", "First", "a1.mp3", 2001, 1, 0, false),
                new ImportedQuery.Item("a2", true, "Two", "Band", "First", "a2.mp3", 0, 2, 0, false),
                new ImportedQuery.Item("b1", true, "Three", "Alice", "Mix", "b1.mp3", 2005, 1, 0, false),
                new ImportedQuery.Item("b2", true, "Four", "Bob", "Mix", "b2.mp3", 2005, 2, 0, false),
                new ImportedQuery.Item("n1", true, "Loose", "Solo", "", "n1.mp3", 0, 0, 0, false));
        // "First" and "Mix" are the albums; the file without an album is not one.
        assertEquals(2, ImportedQuery.shown(items, "", false, ImportedQuery.Sort.ALBUM).albums);
        assertEquals(0, ImportedQuery.shown(items, "", false, ImportedQuery.Sort.TITLE).albums);
        List<ImportedQuery.Item> sorted = new java.util.ArrayList<>(items);
        ImportedQuery.albumYears(sorted);
        sorted.sort(ImportedQuery.comparator(ImportedQuery.Sort.ALBUM));
        List<ImportedQuery.AlbumGroup> groups = ImportedQuery.albumGroups(sorted);
        assertEquals("First", groups.get(0).album);
        assertEquals("Band", groups.get(0).artist);
        assertEquals(2001, groups.get(0).year);
        assertEquals(2, groups.get(0).count);
        assertEquals("Mix", groups.get(1).album);
        assertEquals("", groups.get(1).artist);
        assertEquals("", groups.get(2).album);
        assertEquals(1, groups.get(2).count);
    }

    @Test
    public void sameNameAlbumsOfTwoYearsCountTwiceATrackWithoutYearAndFilesWithoutAlbumNotAtAll() {
        List<ImportedQuery.Item> items = Arrays.asList(
                new ImportedQuery.Item("h1", true, "A", "X", "Hits", "h1.mp3", 1999, 1, 0, false),
                new ImportedQuery.Item("h2", true, "B", "Y", "Hits", "h2.mp3", 2015, 1, 0, false),
                new ImportedQuery.Item("h3", true, "C", "Z", "Hits", "h3.mp3", 0, 1, 0, false),
                new ImportedQuery.Item("s1", true, "D", "W", "", "s1.mp3", 0, 0, 0, false));
        assertEquals(2, ImportedQuery.shown(items, "", false, ImportedQuery.Sort.ALBUM).albums);
        assertEquals(Arrays.asList("h1", "h2", "h3", "s1"),
                ImportedQuery.shown(items, "", false, ImportedQuery.Sort.ALBUM).urns);
    }

    @Test
    public void theLengthSurvivesTheKeptRecordAndAnOlderRecordIsReadAgain() {
        FileTags.Tags tags = new FileTags.Tags("Title", "Artist", "Album", 2019, 3, 320_000, 215_000);
        assertEquals(215_000, FileTags.Tags.parse(tags.format()).durationMs);
        assertNull(FileTags.Tags.parse("tab20193320000"));
    }
}
