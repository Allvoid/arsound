package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SongKeysTest {
    @Test
    public void bracketsFeaturingAndNoiseAreDropped() {
        assertEquals("song", SongKeys.key("Song (Official Video)", false));
        assertEquals("song", SongKeys.key("Song [Free Download]", false));
        assertEquals("song", SongKeys.key("Song feat. Someone", false));
        assertEquals("song", SongKeys.key("SONG HQ", false));
    }

    @Test
    public void artistPrefixIsCut() {
        assertEquals("title", SongKeys.key("Artist - Title", false));
        // A dash at the very start or end is not an "Artist - Title" split.
        assertEquals("title", SongKeys.key("- Title", false));
    }

    @Test
    public void editMarkersStayInTheKeyUnlessMerged() {
        assertEquals("song remix", SongKeys.key("Song (Remix)", false));
        assertEquals("song", SongKeys.key("Song (Remix)", true));
        assertEquals("song slowed reverb", SongKeys.key("Song slowed + reverb", false));
        assertEquals("song speed", SongKeys.key("Song 0.9x", false));
        assertEquals("song", SongKeys.key("Song 0.9x", true));
    }

    @Test
    public void nullTitleHasNoKey() {
        assertEquals("", SongKeys.key(null, false));
        assertEquals("", SongKeys.key(null, true));
    }

    @Test
    public void russianTitlesKeepTheirLetters() {
        assertEquals("кукла колдуна", SongKeys.key("Кукла колдуна (Official Audio)", false));
    }

    @Test
    public void editedVersionsAreRecognised() {
        assertTrue(SongKeys.isEdited("Song (Sped Up)"));
        assertTrue(SongKeys.isEdited("Song 1.25x"));
        assertTrue(SongKeys.isEdited("Song Remix"));
        assertFalse(SongKeys.isEdited("Song"));
        assertFalse(SongKeys.isEdited(null));
    }

    @Test
    public void sameSongNeedsTheSameTitleAndNearLength() {
        assertTrue(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Song (Official Audio)", 181_500)));
        assertTrue(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Song", 182_000)));
        assertFalse(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Song", 182_001)));
        assertFalse(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Other", 180_000)));
    }

    @Test
    public void aRemixIsAnotherSongInTheForYouComparison() {
        assertFalse(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Song (Remix)", 180_000)));
        assertFalse(new SongRef("Song", 180_000).isSameSongAs(new SongRef("Song sped up", 180_000)));
    }

    @Test
    public void emptyKeysNeverMatch() {
        assertFalse(new SongRef("", 1_000).isSameSongAs(new SongRef("", 1_000)));
        assertFalse(new SongRef("(live)", 1_000).isSameSongAs(new SongRef("[hd]", 1_000)));
    }
}
