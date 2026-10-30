package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CrossfadeTest {
    private static final long FADE = 6_000;
    private static final long LENGTH = 180_000;

    @Test
    public void theMiddleOfATrackIsAtFullVolume() {
        assertEquals(1f, Crossfade.volumeAt(60_000, LENGTH, true, FADE), 0f);
        assertEquals(1f, Crossfade.volumeAt(60_000, LENGTH, false, FADE), 0f);
    }

    @Test
    public void theEndFadesOutToSilence() {
        assertEquals(1f, Crossfade.volumeAt(LENGTH - FADE, LENGTH, false, FADE), 0f);
        assertEquals(0.25f, Crossfade.volumeAt(LENGTH - FADE / 2, LENGTH, false, FADE), 1e-6f);
        assertEquals(0f, Crossfade.volumeAt(LENGTH, LENGTH, false, FADE), 0f);
        assertEquals(0f, Crossfade.volumeAt(LENGTH + 500, LENGTH, false, FADE), 0f);
    }

    @Test
    public void theStartFadesInOnlyWhenAsked() {
        assertEquals(0f, Crossfade.volumeAt(0, LENGTH, true, FADE), 0f);
        assertEquals(0.25f, Crossfade.volumeAt(FADE / 2, LENGTH, true, FADE), 1e-6f);
        assertEquals(1f, Crossfade.volumeAt(0, LENGTH, false, FADE), 0f);
    }

    @Test
    public void anUnknownLengthOnlyFadesIn() {
        assertEquals(0f, Crossfade.volumeAt(0, 0, true, FADE), 0f);
        assertEquals(1f, Crossfade.volumeAt(FADE, 0, true, FADE), 0f);
        assertEquals(1f, Crossfade.volumeAt(500, 0, false, FADE), 0f);
    }
}
