package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrossfadeOverlapTest {
    private static final long FADE = 6_000;
    private static final long FADE_STARTS = 1_000;

    @Test
    public void theSecondPlayerStartsALittleBeforeTheFade() {
        // The lead is 250 ms: the player is started early enough to be heard from the fade's start.
        assertEquals(FADE_STARTS - 250, CrossfadeOverlap.startAt(FADE_STARTS));
    }

    @Test
    public void theGaplessChangeStartsJustBeforeTheEndNotEarlier() {
        // No fade: the sound is not faded in, so the lead is only what a started player needs to make its first sound.
        assertEquals(FADE_STARTS - 80, CrossfadeOverlap.startAt(FADE_STARTS, 0));
        assertEquals(FADE_STARTS - 250, CrossfadeOverlap.startAt(FADE_STARTS, FADE));
    }

    @Test
    public void withoutAFadeTheSecondPlayerIsFullFromTheEndOfTheTrack() {
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS - 1, FADE_STARTS, 0), 0f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + 1, FADE_STARTS, 0), 0f);
    }

    @Test
    public void theFadeInIsSilentBeforeItStartsAndFullAtItsEnd() {
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS - 1, FADE_STARTS, FADE), 0f);
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS, FADE_STARTS, FADE), 0f);
        assertEquals(0.25f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + FADE / 2, FADE_STARTS, FADE), 1e-6f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + FADE, FADE_STARTS, FADE), 0f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + 2 * FADE, FADE_STARTS, FADE), 0f);
    }

    @Test
    public void aFadeWithoutLengthDoesNotDivideByZero() {
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(5_000, 4_000, 0), 0f);
    }

    @Test
    public void theHandoffFadesOutUnderSoundCloud() {
        assertEquals(1f, CrossfadeOverlap.handoffLevel(500, 500), 0f);
        assertEquals(0.5f, CrossfadeOverlap.handoffLevel(580, 500), 1e-6f);
        assertEquals(0f, CrossfadeOverlap.handoffLevel(660, 500), 0f);
        assertEquals(0f, CrossfadeOverlap.handoffLevel(9_000, 500), 0f);
    }

    @Test
    public void theSecondPlayerPlaysAtTheSpeedSoundCloudWouldGiveTheTrack() {
        // The common choice slows a track, unless its title says it is retimed already.
        assertTrue(CrossfadeOverlap.playsSlowed(null, VersionSpeed.SLOWED, false));
        assertFalse(CrossfadeOverlap.playsSlowed(null, VersionSpeed.SLOWED, true));
        assertFalse(CrossfadeOverlap.playsSlowed(null, VersionSpeed.NORMAL, false));
        // The track's own speed holds whatever its title says and whatever is chosen for the rest.
        assertTrue(CrossfadeOverlap.playsSlowed(VersionSpeed.SPEED_UP, VersionSpeed.NORMAL, true));
        assertFalse(CrossfadeOverlap.playsSlowed(VersionSpeed.NORMAL, VersionSpeed.SLOWED, false));
    }

    @Test
    public void aSilenceWithAFadeIsAPause() {
        assertFalse(CrossfadeOverlap.isPaused(6_000, 11_000, 10_000, 15_000));
        assertTrue(CrossfadeOverlap.isPaused(6_000, 11_501, 10_000, 10_200));
    }

    @Test
    public void withoutAFadeOnlyASilenceFarFromTheEndIsAPause() {
        // The track played out: the last report was 300 ms before its end.
        assertFalse(CrossfadeOverlap.isPaused(0, 12_000, 10_000, 10_300));
        // Paused with 5 s left.
        assertTrue(CrossfadeOverlap.isPaused(0, 12_000, 10_000, 15_000));
        // Reports are still coming.
        assertFalse(CrossfadeOverlap.isPaused(0, 10_500, 10_000, 15_000));
    }

    @Test
    public void theSecondPlayerIsPreparedEarlierToOpenAStream() {
        // The fade, the 250 ms lead, a second of slack and six seconds for a stream to open.
        assertEquals(6_000 + 250 + 1_000 + 6_000, CrossfadeOverlap.startWindow(6_000));
        assertEquals(0 + 250 + 1_000 + 6_000, CrossfadeOverlap.startWindow(0));
    }
}
