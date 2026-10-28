package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ForYouNamesTest {
    @Test
    public void dashInTheTitleNamesTheArtist() {
        assertArrayEquals(new String[]{"Artist", "Title"}, ForYouNames.artistAndTitle("Artist - Title", "Uploader"));
        assertArrayEquals(new String[]{"Artist", "Title"}, ForYouNames.artistAndTitle("Artist – Title", null));
        assertArrayEquals(new String[]{"Artist", "Title"}, ForYouNames.artistAndTitle("Artist — Title", null));
    }

    @Test
    public void withoutADashTheUploaderIsTheArtist() {
        assertArrayEquals(new String[]{"Uploader", "Title"}, ForYouNames.artistAndTitle(" Title ", " Uploader "));
        assertNull(ForYouNames.artistAndTitle("Title", null));
        assertNull(ForYouNames.artistAndTitle("Title", ""));
        assertNull(ForYouNames.artistAndTitle(null, "Uploader"));
    }

    @Test
    public void dashAtTheEdgesIsNotASplit() {
        assertArrayEquals(new String[]{"Uploader", "- Title"}, ForYouNames.artistAndTitle("- Title", "Uploader"));
        assertNull(ForYouNames.artistAndTitle("Title - ", null));
    }

    @Test
    public void severalArtistsShareTheKeyOfTheFirst() {
        assertEquals("artist", ForYouNames.artistKey("Artist"));
        assertEquals("artist", ForYouNames.artistKey("Artist, Other"));
        assertEquals("artist", ForYouNames.artistKey("Artist & Other"));
        assertEquals("artist", ForYouNames.artistKey("Artist feat. Other"));
        assertEquals("artist", ForYouNames.artistKey("ARTIST ft Other"));
    }

    @Test
    public void anArtistThatStartsWithASeparatorWordKeepsItsWholeName() {
        assertEquals("xambassadors", ForYouNames.artistKey("X Ambassadors"));
        assertNotEquals("", ForYouNames.artistKey("X"));
        assertEquals("x", ForYouNames.artistKey("X"));
    }

    @Test
    public void nullArtistHasAnEmptyKey() {
        assertEquals("", ForYouNames.artistKey(null));
    }

    @Test
    public void songKeyJoinsArtistAndTitleKeys() {
        assertEquals("artist|song remix", ForYouNames.songKey("Artist, Other", "Song (Remix)"));
    }
}
