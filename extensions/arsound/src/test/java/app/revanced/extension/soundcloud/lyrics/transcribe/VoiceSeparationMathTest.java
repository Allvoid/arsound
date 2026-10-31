package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class VoiceSeparationMathTest {
    private static double[] randomFrame(int n, long seed) {
        Random random = new Random(seed);
        double[] frame = new double[n];
        for (int i = 0; i < n; i++) frame[i] = random.nextDouble() * 2 - 1;
        return frame;
    }

    /** The FFT agrees with the definition of the DFT, for both kinds of length. */
    @Test
    public void theTransformIsTheDft() {
        for (int n : new int[]{8, 24, 96}) {
            double[] x = randomFrame(n, n);
            float[] re = new float[n / 2 + 1];
            float[] im = new float[n / 2 + 1];
            new RealFft(n).forward(x, re, im, 0);
            for (int k = 0; k <= n / 2; k++) {
                double sumRe = 0;
                double sumIm = 0;
                for (int t = 0; t < n; t++) {
                    sumRe += x[t] * Math.cos(-2 * Math.PI * k * t / n);
                    sumIm += x[t] * Math.sin(-2 * Math.PI * k * t / n);
                }
                assertEquals("re " + n + "/" + k, sumRe, re[k], 1e-4);
                assertEquals("im " + n + "/" + k, sumIm, im[k], 1e-4);
            }
        }
    }

    @Test
    public void theInverseGivesTheFrameBack() {
        int n = 6144;
        RealFft fft = new RealFft(n);
        double[] x = randomFrame(n, 7);
        float[] re = new float[n / 2 + 1];
        float[] im = new float[n / 2 + 1];
        fft.forward(x, re, im, 0);
        double[] back = new double[n];
        fft.inverse(re, im, 0, back);
        for (int i = 0; i < n; i++) assertEquals(x[i], back[i], 1e-4);
    }

    /** The STFT and its inverse give the signal back: the frames overlap enough for the window to cancel. */
    @Test
    public void theStftGivesTheSignalBack() {
        int nFft = 96;
        int hop = 16;
        MdxStft stft = new MdxStft(nFft, hop);
        int length = hop * 40;
        float[] signal = new float[length];
        Random random = new Random(3);
        for (int i = 0; i < length; i++) signal[i] = random.nextFloat() * 2 - 1;
        int frames = stft.frames(length);
        float[] re = new float[frames * stft.bins()];
        float[] im = new float[frames * stft.bins()];
        stft.forward(signal, 0, length, re, im);
        float[] back = stft.inverse(re, im, frames);
        assertEquals(length, back.length);
        for (int i = 0; i < length; i++) assertEquals(signal[i], back[i], 1e-3);
    }

    /** The buffers kept between calls start clean: a second signal comes back as itself, not mixed with the first. */
    @Test
    public void theInverseKeepsNoTraceOfTheCallBefore() {
        int nFft = 96;
        int hop = 16;
        MdxStft stft = new MdxStft(nFft, hop);
        int length = hop * 40;
        int frames = stft.frames(length);
        Random random = new Random(5);
        for (int round = 0; round < 2; round++) {
            float[] signal = new float[length];
            for (int i = 0; i < length; i++) signal[i] = random.nextFloat() * 2 - 1;
            float[] re = new float[frames * stft.bins()];
            float[] im = new float[frames * stft.bins()];
            stft.forward(signal, 0, length, re, im);
            float[] back = stft.inverse(re, im, frames);
            for (int i = 0; i < length; i++) assertEquals(signal[i], back[i], 1e-3);
        }
    }

    /** The pieces of a song cover it once, in order, each with the margins its neighbours give. */
    @Test
    public void thePiecesCoverTheSongOnce() throws Exception {
        int chunk = 100;
        int margin = 10;
        int total = 345;
        List<int[]> pieces = new ArrayList<>();
        List<Float> voice = new ArrayList<>();
        VocalsStream stream = new VocalsStream(chunk, margin, (left, right, first, last) -> {
            pieces.add(new int[]{(int) left[0], left.length, first ? 1 : 0, last ? 1 : 0});
            // "Separation" keeps the samples; the piece's own part is what lies between the margins it has.
            int start = first ? 0 : margin;
            int end = left.length - (last ? 0 : margin);
            return new float[][]{java.util.Arrays.copyOfRange(left, start, end),
                    java.util.Arrays.copyOfRange(right, start, end)};
        }, (mono, count) -> {
            for (int i = 0; i < count; i++) voice.add(mono[i]);
        });
        float[] samples = new float[total];
        for (int i = 0; i < total; i++) samples[i] = i;
        // Given in uneven blocks, like a decoder does.
        for (int from = 0; from < total; from += 37) {
            int count = Math.min(37, total - from);
            float[] block = java.util.Arrays.copyOfRange(samples, from, from + count);
            stream.push(block, block, count);
        }
        stream.finish();
        assertEquals(total, voice.size());
        for (int i = 0; i < total; i++) assertEquals(i, voice.get(i), 0f);
        // Like sherpa-onnx: [0, 110), [90, 210), [190, 310), [290, 345).
        assertArrayEquals(new int[]{0, 110, 1, 0}, pieces.get(0));
        assertArrayEquals(new int[]{90, 120, 0, 0}, pieces.get(1));
        assertArrayEquals(new int[]{190, 120, 0, 0}, pieces.get(2));
        assertArrayEquals(new int[]{290, 55, 0, 1}, pieces.get(3));
        assertEquals(4, pieces.size());
    }

    @Test
    public void aShortSongIsOnePiece() throws Exception {
        List<Integer> lengths = new ArrayList<>();
        VocalsStream stream = new VocalsStream(100, 10, (left, right, first, last) -> {
            assertTrue(first && last);
            lengths.add(left.length);
            return new float[][]{left, right};
        }, (mono, count) -> {
        });
        float[] block = new float[60];
        stream.push(block, block, 60);
        stream.finish();
        assertEquals(1, lengths.size());
        assertEquals(60, (int) lengths.get(0));
    }
}
