package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChunkerTest {
    private static final int RATE = Chunker.RATE;

    /** A loud 220 Hz tone for the given seconds, with a quiet gap at {@code gapStartSec} for {@code gapSec}. */
    private static float[] tone(int seconds, double gapStartSec, double gapSec) {
        float[] pcm = new float[seconds * RATE];
        for (int i = 0; i < pcm.length; i++) {
            double t = (double) i / RATE;
            boolean gap = t >= gapStartSec && t < gapStartSec + gapSec;
            pcm[i] = gap ? 0.0005f : (float) (0.3 * Math.sin(2 * Math.PI * 220 * t));
        }
        return pcm;
    }

    @Test
    public void aShortSongIsOneWindow() {
        float[] pcm = tone(25, -1, 0);
        assertEquals(pcm.length, Chunker.windowEnd(pcm, 0));
    }

    @Test
    public void aLongSongIsCutInTheQuietPlace() {
        float[] pcm = tone(70, 27.0, 0.6);
        int end = Chunker.windowEnd(pcm, 0);
        assertTrue("cut at " + end, end >= 27 * RATE && end <= 27.7 * RATE);
        assertTrue(end <= Chunker.MAX_WINDOW);
    }

    @Test
    public void aWindowNeverPassesTheLimitWhenThereIsNoGap() {
        float[] pcm = tone(70, -1, 0);
        int end = Chunker.windowEnd(pcm, 0);
        assertTrue(end > 24 * RATE && end <= Chunker.MAX_WINDOW);
    }

    @Test
    public void theNextWindowStartsWhereTheLastEnded() {
        float[] pcm = tone(95, 27.0, 0.6);
        int first = Chunker.windowEnd(pcm, 0);
        int second = Chunker.windowEnd(pcm, first);
        assertTrue(second > first && second - first <= Chunker.MAX_WINDOW);
    }

    @Test
    public void silenceIsRecognised() {
        float[] quiet = new float[10 * RATE];
        assertTrue(Chunker.isSilent(quiet, 0, quiet.length));
        float[] loud = tone(10, -1, 0);
        assertFalse(Chunker.isSilent(loud, 0, loud.length));
    }

    @Test
    public void theActiveSpanSkipsQuietEnds() {
        float[] pcm = new float[20 * RATE];
        for (int i = 5 * RATE; i < 15 * RATE; i++) pcm[i] = (float) (0.3 * Math.sin(2 * Math.PI * 220 * i / RATE));
        int[] span = Chunker.activeSpan(pcm, 0, pcm.length);
        assertTrue("start " + span[0], Math.abs(span[0] - 5 * RATE) <= RATE / 5);
        assertTrue("end " + span[1], Math.abs(span[1] - 15 * RATE) <= RATE / 5);
    }

    @Test
    public void aTooShortPieceIsTakenWhole() {
        float[] pcm = new float[RATE / 4];
        int[] span = Chunker.activeSpan(pcm, 0, pcm.length);
        assertEquals(0, span[0]);
        assertEquals(pcm.length, span[1]);
    }
}
