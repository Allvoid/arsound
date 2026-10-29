package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Takes the voice out of a piece of a song with UVR-MDX-NET Voc_FT, run by ONNX Runtime: the music under the voice is
 * what made the speech models hear nonsense. The same steps as sherpa-onnx's {@code OfflineSourceSeparationUvrImpl}
 * (that library has no Java interface for it): the stereo piece is cut into segments of {@code dim_t} frames, each
 * goes through the model as its spectrum (the lower {@code dim_f} bins of both channels, real and imaginary parts),
 * and the voice comes back by the inverse transform.
 * <p>
 * The model's own description (its ONNX metadata) gives the sizes. One separator holds the model (about a third of a
 * gigabyte of memory) for one song and is {@link #close closed} after it. Used through {@link VocalsStream}.
 */
final class MdxSeparator implements AutoCloseable, VocalsStream.Chunks {
    /** The voice comes out at the model's rate; the separator is fed at it. */
    static final int RATE = 44_100;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String input;
    private final int nFft;
    private final int dimF;
    private final int dimT;
    private final int trim;
    /** Samples one segment covers, and the samples it gives back once its padding is cut off. */
    private final int size;
    private final int step;
    private final int margin;
    private final MdxStft stft;

    MdxSeparator(File model, int threads) throws IOException {
        OrtLibs.load();
        try {
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.getAbsolutePath(), options);
            Map<String, String> meta = session.getMetadata().getCustomMetadata();
            nFft = number(meta, "n_fft");
            dimF = number(meta, "dim_f");
            dimT = number(meta, "dim_t");
            int hop = number(meta, "hop_length");
            if (number(meta, "dim_c") != 4 || number(meta, "sample_rate") != RATE || number(meta, "win_length") != nFft
                    || !"hann".equals(meta.get("window_type"))) {
                throw new IOException("A separation model of another kind: " + meta);
            }
            input = session.getInputNames().iterator().next();
            trim = nFft / 2;
            size = hop * (dimT - 1);
            step = size - 2 * trim;
            margin = RATE;
            stft = new MdxStft(nFft, hop);
        } catch (OrtException ex) {
            close();
            throw new IOException("The separation model could not be loaded: " + ex.getMessage(), ex);
        }
    }

    private static int number(Map<String, String> meta, String key) throws IOException {
        String value = meta.get(key);
        if (value == null) throw new IOException("The separation model does not say its " + key);
        return Integer.parseInt(value.trim());
    }

    /** Samples cut off both ends of a piece that is not at the start or the end of the song. */
    int margin() {
        return margin;
    }

    /**
     * The voice of one piece, both channels. The piece carries {@link #margin} extra samples on the sides that have
     * a neighbour; they are cut off again, so the pieces join without a seam.
     */
    @Override
    public float[][] separate(float[] left, float[] right, boolean first, boolean last) throws IOException {
        int length = left.length;
        int pad = step - length % step;
        float[] padded0 = new float[trim + length + pad + trim];
        float[] padded1 = new float[padded0.length];
        System.arraycopy(left, 0, padded0, trim, length);
        System.arraycopy(right, 0, padded1, trim, length);
        int segments = (length + pad) / step;
        float[] voice0 = new float[segments * step];
        float[] voice1 = new float[segments * step];
        int bins = stft.bins();
        int frames = stft.frames(size);
        float[] re0 = new float[frames * bins];
        float[] im0 = new float[frames * bins];
        float[] re1 = new float[frames * bins];
        float[] im1 = new float[frames * bins];
        float[] x = new float[4 * dimF * dimT];
        for (int segment = 0; segment < segments; segment++) {
            int from = segment * step;
            stft.forward(padded0, from, size, re0, im0);
            stft.forward(padded1, from, size, re1, im1);
            pack(re0, x, 0, bins);
            pack(im0, x, 1, bins);
            pack(re1, x, 2, bins);
            pack(im1, x, 3, bins);
            float[] y = run(x);
            unpack(y, re0, 0, bins);
            unpack(y, im0, 1, bins);
            unpack(y, re1, 2, bins);
            unpack(y, im1, 3, bins);
            System.arraycopy(stft.inverse(re0, im0, frames), trim, voice0, from, step);
            System.arraycopy(stft.inverse(re1, im1, frames), trim, voice1, from, step);
        }
        int start = first ? 0 : margin;
        int end = voice0.length - pad - (last ? 0 : margin);
        return new float[][]{
                java.util.Arrays.copyOfRange(voice0, start, end),
                java.util.Arrays.copyOfRange(voice1, start, end)};
    }

    /** One plane of the model's input: bin {@code f} of frame {@code t} goes to {@code [plane][f][t]}. */
    private void pack(float[] spectrum, float[] x, int plane, int bins) {
        int base = plane * dimF * dimT;
        for (int f = 0; f < dimF; f++) {
            int row = base + f * dimT;
            for (int t = 0; t < dimT; t++) x[row + t] = spectrum[t * bins + f];
        }
    }

    /** The plane back into the spectrum; the bins above {@code dim_f}, which the model does not see, are silenced. */
    private void unpack(float[] y, float[] spectrum, int plane, int bins) {
        int base = plane * dimF * dimT;
        for (int t = 0; t < dimT; t++) {
            int frame = t * bins;
            for (int f = 0; f < dimF; f++) spectrum[frame + f] = y[base + f * dimT + t];
            for (int f = dimF; f < bins; f++) spectrum[frame + f] = 0;
        }
    }

    private float[] run(float[] x) throws IOException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(x), new long[]{1, 4, dimF, dimT});
             OrtSession.Result result = session.run(Collections.singletonMap(input, tensor))) {
            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] y = new float[x.length];
            output.get(y);
            return y;
        } catch (OrtException ex) {
            throw new IOException("The separation model failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
        } catch (OrtException ignored) {
            // Nothing more to free.
        }
    }
}
