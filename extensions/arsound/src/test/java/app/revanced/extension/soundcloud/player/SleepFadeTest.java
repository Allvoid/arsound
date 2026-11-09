package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SleepFadeTest {
    private static final long FADE = SleepFade.FADE_MS;

    @Test
    public void fullVolumeBeforeTheFade() {
        assertEquals(1f, SleepFade.fadeLevel(FADE, FADE), 0f);
        assertEquals(1f, SleepFade.fadeLevel(FADE + 10_000, FADE), 0f);
    }

    @Test
    public void silenceAtTheStop() {
        assertEquals(0f, SleepFade.fadeLevel(0, FADE), 0f);
        assertEquals(0f, SleepFade.fadeLevel(-500, FADE), 0f);
    }

    @Test
    public void theFadeFollowsTheSquareOfTheWay() {
        assertEquals(0.25f, SleepFade.fadeLevel(FADE / 2, FADE), 1e-6f);
        float earlier = SleepFade.fadeLevel(FADE - 1000, FADE);
        float later = SleepFade.fadeLevel(1000, FADE);
        assertTrue(earlier > later);
    }

    @Test
    public void aShortFadeStartsAtFullVolume() {
        // A timer set for 20 seconds fades over those 20 seconds, not from the middle of a 45-second fade.
        assertEquals(1f, SleepFade.fadeLevel(20_000, 20_000), 0f);
    }

    @Test
    public void theRiseGoesStraightToFullVolume() {
        assertEquals(0.2f, SleepFade.riseLevel(0.2f, 0, SleepFade.RISE_MS), 1e-6f);
        assertEquals(0.6f, SleepFade.riseLevel(0.2f, SleepFade.RISE_MS / 2, SleepFade.RISE_MS), 1e-6f);
        assertEquals(1f, SleepFade.riseLevel(0.2f, SleepFade.RISE_MS, SleepFade.RISE_MS), 0f);
        assertEquals(1f, SleepFade.riseLevel(0f, SleepFade.RISE_MS * 3, SleepFade.RISE_MS), 0f);
    }

    @Test
    public void theLevelsMultiply() {
        assertEquals(0.25f, PlayerVolume.product(0.5f, 0.5f), 1e-6f);
        assertEquals(0f, PlayerVolume.product(1f, 0f), 0f);
        assertEquals(1f, PlayerVolume.product(1f, 1f), 0f);
        assertEquals(1f, PlayerVolume.product(1.5f, 2f), 0f);
    }
}
