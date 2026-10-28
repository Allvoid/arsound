package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackMatcherTest {
    @Test
    public void normalizeKeepsLettersAndDigitsOnly() {
        assertEquals("ещеraz2", TrackMatcher.normalize("Ёще, Raz-2!"));
    }

    @Test
    public void artistMayBeTheUploaderOrInTheTitle() {
        assertTrue(TrackMatcher.isByArtist("CUPSIZE Official", "Song", "cupsize"));
        assertTrue(TrackMatcher.isByArtist("some uploader", "CUPSIZE - Song", "CUPSIZE"));
        assertFalse(TrackMatcher.isByArtist("someone", "Song", "CUPSIZE"));
    }

    @Test
    public void artistPrefixIsStrippedWithItsDash() {
        assertEquals("17", TrackMatcher.stripArtist("CUPSIZE - 17", "cupsize"));
        assertEquals("Song", TrackMatcher.stripArtist("Song", "cupsize"));
    }

    @Test
    public void titleMustHoldTheWholeWantedTitle() {
        assertFalse(TrackMatcher.holdsTitle("Сахарная вата", "вата"));
        assertTrue(TrackMatcher.holdsTitle("Sugar cotton candy (feat. X)", "cotton candy"));
    }

    @Test
    public void shortTitleMustBeTheWholeTitle() {
        assertTrue(TrackMatcher.holdsTitle("CUPSIZE - 17", "17"));
        assertFalse(TrackMatcher.holdsTitle("17 ножевых", "17"));
    }

    @Test
    public void emptyTitleMatchesNothing() {
        assertFalse(TrackMatcher.holdsTitle("anything", "(live)"));
    }

    @Test
    public void wantedVersionMustBeNamedByTheFoundTitle() {
        assertTrue(TrackMatcher.namesVersion("В окно с тобой (Slow Version)", "В окно с тобой (Slow Version)"));
        assertFalse(TrackMatcher.namesVersion("В окно с тобой", "В окно с тобой (Slow Version)"));
        // A note that is not a version, such as a guest, is not required.
        assertTrue(TrackMatcher.namesVersion("Song", "Song (feat. Guest)"));
    }

    @Test
    public void variantsAreRejectedUnlessWanted() {
        assertTrue(TrackMatcher.isVariant("Song (sped up)", "Song"));
        assertFalse(TrackMatcher.isVariant("Song (sped up)", "Song sped up"));
        assertTrue(TrackMatcher.isVariant("Song 1.25x", "Song"));
        assertFalse(TrackMatcher.isVariant("Song", "Song"));
    }

    @Test
    public void snippetsAreRejectedUnlessWanted() {
        assertTrue(TrackMatcher.isSnippet("Song snippet", "Song"));
        assertFalse(TrackMatcher.isSnippet("Song snippet", "Song snippet"));
    }

    @Test
    public void wantedTrackNeedsArtistTitleAndNoVariant() {
        assertTrue(TrackMatcher.isWantedTrack("cupsize", "CUPSIZE - Night Drive", "CUPSIZE", "Night Drive"));
        assertFalse(TrackMatcher.isWantedTrack("cupsize", "CUPSIZE - Night Drive (remix)", "CUPSIZE", "Night Drive"));
        assertFalse(TrackMatcher.isWantedTrack("other", "Night Drive", "CUPSIZE", "Night Drive"));
    }

    @Test
    public void importedFileMatchesOnItsTagTitle() {
        assertTrue(TrackMatcher.isImportedMatch("CUPSIZE - Night Drive", "Night Drive"));
        assertFalse(TrackMatcher.isImportedMatch("Other", "Night Drive"));
        assertFalse(TrackMatcher.isImportedMatch("Anything", "(live)"));
    }
}
