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
    @Test
    public void betweenReportsThePositionRunsAtTheTracksSpeed() {
        // Review r16: 500 ms of real time are 700 ms of the track at 140 %, 425 ms at 85 %; an unknown factor counts as 1.
        org.junit.Assert.assertEquals(700, Crossfade.mediaElapsed(500, 1.4f));
        org.junit.Assert.assertEquals(425, Crossfade.mediaElapsed(500, 0.85f));
        org.junit.Assert.assertEquals(500, Crossfade.mediaElapsed(500, 1f));
        org.junit.Assert.assertEquals(500, Crossfade.mediaElapsed(500, 0f));
    }
}
