package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ChunkerSplitTest {
    private static final int SECOND = Chunker.RATE;

    @Test
    public void aWindowIsCutInTheQuietPlaceNearItsMiddle() {
        float[] pcm = new float[20 * SECOND];
        Arrays.fill(pcm, 0.5f);
        // A gap of a second, a little after the middle (10 s).
        Arrays.fill(pcm, 9_400 * SECOND / 1000, 10_600 * SECOND / 1000, 0f);
        int cut = Chunker.splitPoint(pcm, 0, pcm.length);
        assertTrue("cut at " + cut, cut >= 9_400 * SECOND / 1000 && cut <= 10_600 * SECOND / 1000);
    }

    @Test
    public void aWindowThatIsEvenlyLoudIsCutNearTheMiddle() {
        float[] pcm = new float[20 * SECOND];
        Arrays.fill(pcm, 0.3f);
        int cut = Chunker.splitPoint(pcm, 0, pcm.length);
        assertTrue("cut at " + cut, cut >= 8 * SECOND && cut <= 12 * SECOND);
    }

    @Test
    public void theCutOfALaterWindowIsMeasuredFromItsStart() {
        float[] pcm = new float[40 * SECOND];
        Arrays.fill(pcm, 0.5f);
        Arrays.fill(pcm, 29 * SECOND, 31 * SECOND, 0f);
        int cut = Chunker.splitPoint(pcm, 20 * SECOND, 40 * SECOND);
        assertTrue("cut at " + cut, cut >= 29 * SECOND && cut <= 31 * SECOND);
    }

    @Test
    public void samplesAreTurnedIntoMilliseconds() {
        assertEquals(1000L, Chunker.toMs(SECOND));
        assertEquals(0L, Chunker.toMs(0));
        assertEquals(30_000L, Chunker.toMs(30 * SECOND));
    }
}
