package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WaveDedupeTest {
    private static SongRef song(String title, long duration) {
        return new SongRef(title, duration);
    }

    @Test
    public void aCopyOfASongInTheQueueIsLeftOut() {
        WaveDedupe dedupe = new WaveDedupe(Collections.singletonList(song("Blue Lantern", 200_000)));
        assertFalse(dedupe.admit(song("Blue Lantern (Official Audio)", 201_000)));
        assertTrue(dedupe.admit(song("Green Harbor", 200_000)));
        assertEquals(1, dedupe.dropped());
    }

    @Test
    public void theSecondCopyInOneBatchIsLeftOut() {
        WaveDedupe dedupe = new WaveDedupe(null);
        assertTrue(dedupe.admit(song("Quiet Orchard", 180_000)));
        assertFalse(dedupe.admit(song("quiet orchard", 180_500)));
        assertFalse(dedupe.admit(song("Quiet Orchard [HQ]", 179_000)));
        assertEquals(2, dedupe.dropped());
    }

    @Test
    public void aRemixOrAnotherLengthIsAnotherSong() {
        WaveDedupe dedupe = new WaveDedupe(Collections.singletonList(song("Paper Moon", 200_000)));
        assertTrue(dedupe.admit(song("Paper Moon (Night Remix)", 200_000)));
        assertTrue(dedupe.admit(song("Paper Moon", 260_000)));
    }

    @Test
    public void aTrackWithoutAKnownSongAlwaysStays() {
        WaveDedupe dedupe = new WaveDedupe(Collections.singletonList(song("Paper Moon", 200_000)));
        assertTrue(dedupe.admit(null));
        assertTrue(dedupe.admit(null));
        assertTrue(dedupe.admit(song("", 0)));
        assertTrue(dedupe.admit(song("", 0)));
        assertEquals(0, dedupe.dropped());
    }

    @Test
    public void filterKeepsTheOrderAndStopsAtTheLimit() {
        Map<String, SongRef> known = new HashMap<>();
        known.put("a", song("Amber Road", 190_000));
        known.put("b", song("Amber Road", 190_200));
        known.put("c", song("Birch Line", 210_000));
        known.put("d", song("Cedar Hill", 220_000));
        WaveDedupe dedupe = new WaveDedupe(null);
        List<String> kept = dedupe.filter(Arrays.asList("a", "b", "c", "x", "d"), known::get, 3);
        // "x" is not known: it stays, and the limit cuts the list after it.
        assertEquals(Arrays.asList("a", "c", "x"), kept);
    }

    @Test
    public void theSongsOfTheQueueAreTheKnownOnes() {
        Map<String, SongRef> known = new HashMap<>();
        known.put("q1", song("Amber Road", 190_000));
        List<SongRef> songs = WaveDedupe.songsOf(Arrays.asList("q1", "q2"), known::get);
        assertEquals(1, songs.size());
        WaveDedupe dedupe = new WaveDedupe(songs);
        assertFalse(dedupe.admit(song("Amber Road", 190_000)));
    }
}
