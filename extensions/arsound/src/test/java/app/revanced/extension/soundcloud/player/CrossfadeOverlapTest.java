package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;

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
}
