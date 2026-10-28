package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class TitleCleanerTest {
    @Test
    public void junkTagsAreDropped() {
        TitleCleaner.Cleaned cleaned = TitleCleaner.clean("Artist - Song [Official Video] (Lyrics)", true);
        assertEquals("Artist - Song", cleaned.title);
        assertFalse(cleaned.versionTagged);
    }

    @Test
    public void guestsInATagAreCollected() {
        TitleCleaner.Cleaned cleaned = TitleCleaner.clean("Song (feat. A & B)", true);
        assertEquals("Song", cleaned.title);
        assertEquals(Arrays.asList("A", "B"), cleaned.featured);
    }

    @Test
    public void guestsOutsideBracketsAreCollected() {
        TitleCleaner.Cleaned cleaned = TitleCleaner.clean("Song ft. Someone", true);
        assertEquals("Song", cleaned.title);
        assertEquals(Arrays.asList("Someone"), cleaned.featured);
    }

    @Test
    public void versionTagIsNoted() {
        TitleCleaner.Cleaned cleaned = TitleCleaner.clean("Song (Slowed + Reverb)", true);
        assertEquals("Song", cleaned.title);
        assertTrue(cleaned.versionTagged);
    }

    @Test
    public void unknownBracketsStayWhenAsked() {
        assertEquals("Song (Part 2)", TitleCleaner.clean("Song (Part 2)", false).title);
        assertEquals("Song", TitleCleaner.clean("Song (Part 2)", true).title);
    }

    @Test
    public void producerCreditIsCutOff() {
        assertEquals("Song", TitleCleaner.clean("Song prod. by Somebody", true).title);
    }

    @Test
    public void retimedVersionsAreRecognised() {
        assertTrue(TitleCleaner.isRetimed("Song sped up"));
        assertTrue(TitleCleaner.isRetimed("Song NIGHTCORE"));
        assertFalse(TitleCleaner.isRetimed("Song (Live)"));
    }

    @Test
    public void withoutTagsToleratesNull() {
        assertEquals("", TitleCleaner.withoutTags(null));
        assertEquals("Song", TitleCleaner.withoutTags("Song [HD]"));
    }

    @Test
    public void uploaderLosesChannelWords() {
        assertEquals("Artist", TitleCleaner.cleanUploader("Artist - Topic"));
        assertEquals("Artist", TitleCleaner.cleanUploader("Artist Official"));
        assertEquals("Some Artist", TitleCleaner.cleanUploader("Some_Artist"));
    }

    @Test
    public void uploaderMadeOfChannelWordsKeepsItsName() {
        assertEquals("Music", TitleCleaner.cleanUploader("Music"));
    }
}
