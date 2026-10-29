package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The short-time Fourier transform the voice separation model was trained with (PyTorch's {@code stft}/{@code istft}
 * as kaldi-native-fbank does them in sherpa-onnx): a periodic Hann window as long as the frame, the signal padded by
 * half a frame at both ends with its mirror image, no normalisation; the inverse divides by the summed squares of the
 * window and drops the padding again. Pure arithmetic; not shared between threads.
 * <p>
 * Used by {@link MdxSeparator}.
 */
final class MdxStft {
    private final int nFft;
    private final int hop;
    private final int bins;
    private final double[] window;
    private final RealFft fft;
    private final double[] frame;

    MdxStft(int nFft, int hop) {
        this.nFft = nFft;
        this.hop = hop;
        bins = nFft / 2 + 1;
        window = new double[nFft];
        for (int i = 0; i < nFft; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / nFft);
        fft = new RealFft(nFft);
        frame = new double[nFft];
    }

    int bins() {
        return bins;
    }

    /** How many frames {@link #forward} makes of this many samples. */
    int frames(int length) {
        return 1 + length / hop;
    }

    /**
     * The spectrum of {@code samples[from .. from + length)}, frame after frame: frame {@code t}, bin {@code f} is at
     * {@code t * bins + f}.
     */
    void forward(float[] samples, int from, int length, float[] re, float[] im) {
        int pad = nFft / 2;
        int frames = frames(length);
        for (int t = 0; t < frames; t++) {
            int start = t * hop - pad;
            for (int i = 0; i < nFft; i++) frame[i] = sample(samples, from, length, start + i) * window[i];
            fft.forward(frame, re, im, t * bins);
        }
    }

    /** A sample of the padded signal: positions before the start and after the end are mirrored inside. */
    private static float sample(float[] samples, int from, int length, int position) {
        if (position < 0) position = -position;
        else if (position >= length) position = 2 * (length - 1) - position;
        return samples[from + position];
    }

    /**
     * The signal of a spectrum made by {@link #forward}: {@code (frames - 1) * hop} samples, the half frames of
     * padding taken off.
     */
    float[] inverse(float[] re, float[] im, int frames) {
        int total = nFft + (frames - 1) * hop;
        double[] sum = new double[total];
        double[] weight = new double[total];
        for (int t = 0; t < frames; t++) {
            fft.inverse(re, im, t * bins, frame);
            int start = t * hop;
            for (int i = 0; i < nFft; i++) {
                sum[start + i] += frame[i] * window[i];
                weight[start + i] += window[i] * window[i];
            }
        }
        int pad = nFft / 2;
        float[] out = new float[total - nFft];
        for (int i = 0; i < out.length; i++) {
            double w = weight[pad + i];
            out[i] = (float) (w == 0 ? sum[pad + i] : sum[pad + i] / w);
        }
        return out;
    }
}
