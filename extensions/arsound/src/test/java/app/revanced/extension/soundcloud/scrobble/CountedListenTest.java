package app.revanced.extension.soundcloud.scrobble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CountedListenTest {
    private static final String URN = "soundcloud:tracks:7";

    @Test
    public void theListenCountedBeforeAPauseAndARestartIsKnownAgain() {
        // Track 4:00, counted at 2:00, paused at 3:00, the app killed, play: the resumed listen is the same one.
        String mark = CountedListen.mark(URN, 1_700_000_000L);
        assertTrue(CountedListen.matches(mark, URN, 1_700_000_000L));
        // The statistics and the scrobbler read the clock a moment apart.
        assertTrue(CountedListen.matches(mark, URN, 1_700_000_001L));
    }

    @Test
    public void anotherTrackOrAnotherListenOfTheSameTrackIsNew() {
        String mark = CountedListen.mark(URN, 1_700_000_000L);
        assertFalse(CountedListen.matches(mark, "soundcloud:tracks:8", 1_700_000_000L));
        // Heard again the next day: a new listen that must count.
        assertFalse(CountedListen.matches(mark, URN, 1_700_086_400L));
    }

    @Test
    public void aMissingOrDamagedMarkMatchesNothing() {
        assertFalse(CountedListen.matches(null, URN, 1));
        assertFalse(CountedListen.matches("", URN, 1));
        assertFalse(CountedListen.matches("garbage", URN, 1));
        assertFalse(CountedListen.matches("x	" + URN, URN, 1));
    }
}
