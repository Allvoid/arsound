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
}
