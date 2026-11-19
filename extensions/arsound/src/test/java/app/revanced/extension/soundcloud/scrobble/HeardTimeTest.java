package app.revanced.extension.soundcloud.scrobble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeardTimeTest {
    @Test
    public void halfOfTheLengthCountsForAnOrdinaryTrack() {
        assertEquals(90_000, HeardTime.neededMs(180_000));
    }

    @Test
    public void fourMinutesCountForALongTrack() {
        assertEquals(240_000, HeardTime.neededMs(20 * 60_000));
        assertEquals(240_000, HeardTime.neededMs(480_000));
    }

    @Test
    public void aTrackUnderThirtySecondsNeverCounts() {
        assertEquals(-1, HeardTime.neededMs(29_999));
        assertEquals(15_000, HeardTime.neededMs(30_000));
        HeardTime heard = new HeardTime();
        heard.begin(20_000);
        assertEquals(-1, heard.remainingMs(0));
    }

    @Test
    public void anUnknownLengthNeedsFourMinutes() {
        assertEquals(240_000, HeardTime.neededMs(0));
    }

    @Test
    public void aPauseIsNotHeardTime() {
        HeardTime heard = new HeardTime();
        heard.begin(100_000);
        heard.onPlaying(true, 1_000);
        heard.onPlaying(false, 21_000);
        assertEquals(20_000, heard.heard(500_000));
        heard.onPlaying(true, 600_000);
        assertEquals(30_000, heard.heard(610_000));
        assertEquals(20_000, heard.remainingMs(610_000));
    }

    @Test
    public void remainingFallsToZeroWhenEnoughWasHeard() {
        HeardTime heard = new HeardTime();
        heard.begin(60_000);
        heard.onPlaying(true, 0);
        assertEquals(30_000, heard.remainingMs(0));
        assertEquals(0, heard.remainingMs(30_000));
        assertEquals(0, heard.remainingMs(45_000));
    }

    @Test
    public void aNewTrackStartsTheCountOver() {
        HeardTime heard = new HeardTime();
        heard.begin(60_000);
        heard.onPlaying(true, 0);
        assertEquals(10_000, heard.heard(10_000));
        heard.begin(60_000);
        assertFalse(heard.isPlaying());
        assertEquals(0, heard.heard(10_000));
        heard.onPlaying(true, 10_000);
        assertTrue(heard.isPlaying());
    }

    @Test
    public void aListenTakenUpAfterARestartKeepsWhatWasHeard() {
        HeardTime heard = new HeardTime();
        heard.begin(100_000, 40_000);
        assertEquals(40_000, heard.heard(0));
        assertEquals(10_000, heard.remainingMs(0));
        heard.onPlaying(true, 0);
        assertEquals(0, heard.remainingMs(10_000));
        heard.begin(100_000, -5);
        assertEquals(0, heard.heard(0));
    }

    @Test
    public void startingTwiceDoesNotRestartTheStretch() {
        HeardTime heard = new HeardTime();
        heard.begin(60_000);
        heard.onPlaying(true, 0);
        heard.onPlaying(true, 5_000);
        assertEquals(8_000, heard.heard(8_000));
    }
}
