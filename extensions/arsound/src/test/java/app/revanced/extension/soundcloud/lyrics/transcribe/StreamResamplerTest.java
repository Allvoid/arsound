package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StreamResamplerTest {
    private static float[] sine(int rate, double hz, double seconds) {
        float[] pcm = new float[(int) (rate * seconds)];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / rate));
        return pcm;
    }

    private static float[] resample(float[] input, int from, int to, int block) {
        StreamResampler resampler = new StreamResampler(from, to);
        GrowableFloats out = new GrowableFloats(1024);
        float[] buffer = new float[block];
        for (int offset = 0; offset < input.length; offset += block) {
            int count = Math.min(block, input.length - offset);
            System.arraycopy(input, offset, buffer, 0, count);
            resampler.push(buffer, count, out);
        }
        resampler.finish(out);
        return out.toArray();
    }

    private static double rms(float[] pcm, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (to - from));
    }

    @Test
    public void theLengthFollowsTheRatio() {
        float[] out = resample(sine(44100, 440, 3), 44100, 16000, 4096);
        assertEquals(3 * 16000, out.length, 4);
    }

    @Test
    public void aToneInTheBandKeepsItsLevel() {
        float[] out = resample(sine(44100, 440, 3), 44100, 16000, 4096);
        // 0.5 * sin has an RMS of 0.3536.
        assertEquals(0.3536, rms(out, 8000, 40000), 0.01);
    }

    @Test
    public void aToneAboveTheBandIsRemovedNotFolded() {
        // 12 kHz is above the 8 kHz limit of 16 kHz audio; a poor filter would fold it down to 4 kHz.
        float[] out = resample(sine(44100, 12000, 3), 44100, 16000, 4096);
        assertTrue("level " + rms(out, 8000, 40000), rms(out, 8000, 40000) < 0.01);
    }

    @Test
    public void theBlockSizeDoesNotChangeTheResult() {
        float[] input = sine(48000, 1000, 1);
        assertArrayEquals(resample(input, 48000, 16000, 48000), resample(input, 48000, 16000, 777), 1e-6f);
    }

    @Test
    public void theSameRateKeepsTheSound() {
        float[] input = sine(16000, 500, 1);
        float[] out = resample(input, 16000, 16000, 1000);
        assertEquals(input.length, out.length, 2);
        for (int i = 400; i < 15000; i++) assertEquals(input[i], out[i], 0.01f);
    }

    @Test
    public void aSteadyLevelStaysSteady() {
        float[] input = new float[44100];
        java.util.Arrays.fill(input, 0.25f);
        float[] out = resample(input, 44100, 16000, 1000);
        for (int i = 200; i < out.length - 200; i++) assertEquals(0.25f, out[i], 0.005f);
    }
}
