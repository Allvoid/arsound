package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SongNamesTest {
    @Test
    public void dashInTheTitleNamesTheArtist() {
        assertArrayEquals(new String[]{"Artist", "Title"}, SongNames.artistAndTitle("Artist - Title", "Uploader"));
        assertArrayEquals(new String[]{"Artist", "Title"}, SongNames.artistAndTitle("Artist – Title", null));
        assertArrayEquals(new String[]{"Artist", "Title"}, SongNames.artistAndTitle("Artist — Title", null));
    }

    @Test
    public void withoutADashTheUploaderIsTheArtist() {
        assertArrayEquals(new String[]{"Uploader", "Title"}, SongNames.artistAndTitle(" Title ", " Uploader "));
        assertNull(SongNames.artistAndTitle("Title", null));
        assertNull(SongNames.artistAndTitle("Title", ""));
        assertNull(SongNames.artistAndTitle(null, "Uploader"));
    }

    @Test
    public void dashAtTheEdgesIsNotASplit() {
        assertArrayEquals(new String[]{"Uploader", "- Title"}, SongNames.artistAndTitle("- Title", "Uploader"));
        assertNull(SongNames.artistAndTitle("Title - ", null));
    }

    @Test
    public void severalArtistsShareTheKeyOfTheFirst() {
        assertEquals("artist", SongNames.artistKey("Artist"));
        assertEquals("artist", SongNames.artistKey("Artist, Other"));
        assertEquals("artist", SongNames.artistKey("Artist & Other"));
        assertEquals("artist", SongNames.artistKey("Artist feat. Other"));
        assertEquals("artist", SongNames.artistKey("ARTIST ft Other"));
    }

    @Test
    public void anArtistThatStartsWithASeparatorWordKeepsItsWholeName() {
        assertEquals("xambassadors", SongNames.artistKey("X Ambassadors"));
        assertNotEquals("", SongNames.artistKey("X"));
        assertEquals("x", SongNames.artistKey("X"));
    }

    @Test
    public void nullArtistHasAnEmptyKey() {
        assertEquals("", SongNames.artistKey(null));
    }

    @Test
    public void songKeyJoinsArtistAndTitleKeys() {
        assertEquals("artist|song remix", SongNames.songKey("Artist, Other", "Song (Remix)"));
    }
}
