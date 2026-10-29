package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Takes the voice out of a piece of a song with UVR-MDX-NET Voc_FT, run by ONNX Runtime: the music under the voice is
 * what made the speech models hear nonsense. The same steps as sherpa-onnx's {@code OfflineSourceSeparationUvrImpl}
 * (that library has no Java interface for it): the stereo piece is cut into segments of {@code dim_t} frames, each
 * goes through the model as its spectrum (the lower {@code dim_f} bins of both channels, real and imaginary parts),
 * and the voice comes back by the inverse transform.
 * <p>
 * The model runs on a Snapdragon's neural processor where there is one ({@link Npu}), else on the cores; if the
 * processor fails in the middle, the cores take over. The transforms are done on two helper threads while the model
 * works: the spectrum of the next segment and the voice of the previous one, so the model never waits for them.
 * <p>
 * The model's own description (its ONNX metadata) gives the sizes. One separator holds the model (about a third of a
 * gigabyte of memory on the cores) for one song and is {@link #close closed} after it. Used through
 * {@link VocalsStream}.
 */
final class MdxSeparator implements AutoCloseable, VocalsStream.Chunks {
    /** The voice comes out at the model's rate; the separator is fed at it. */
    static final int RATE = 44_100;

    private final OrtEnvironment environment;
    private final File model;
    private final int threads;
    private OrtSession session;
    /** Whether {@link #session} is on the neural processor. */
    private boolean onNpu;
    private final String input;
    private final String output;
    private final int nFft;
    private final int dimF;
    private final int dimT;
    private final int trim;
    /** Samples one segment covers, and the samples it gives back once its padding is cut off. */
    private final int size;
    private final int step;
    private final int margin;
    /** One transform each for the spectra going in and the voice coming out: they run at the same time. */
    private final MdxStft forward;
    private final MdxStft inverse;
    private final ExecutorService helpers = Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "arsound-voice");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * The model's input and output of two segments: the next input is filled and the last output read while the model
     * works on the current pair.
     */
    private final float[][] inputs;
    private final float[][] outputs;
    /** One channel's spectrum for each transform: frame {@code t}, bin {@code f} at {@code t * bins + f}. */
    private final float[][] spectrumIn;
    private final float[][] spectrumOut;
    /** The model reads and writes these in native memory directly: nothing is allocated per segment. */
    private final FloatBuffer inputBuffer;
    private final FloatBuffer outputBuffer;
    private final OnnxTensor inputTensor;
    private final OnnxTensor outputTensor;

    /** Opens the model on the neural processor if the phone has a usable one, else on {@code threads} cores. */
    static MdxSeparator open(File model, int threads) throws IOException {
        OrtLibs.load();
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        Context context = Utils.getContext();
        OrtSession npu = context == null ? null : Npu.open(context, environment, model);
        OrtSession session = npu != null ? npu : openOnCores(environment, model, threads);
        try {
            return new MdxSeparator(environment, session, npu != null, model, threads);
        } catch (IOException | RuntimeException ex) {
            closeQuietly(session);
            throw ex;
        }
    }

    MdxSeparator(OrtEnvironment environment, OrtSession session, boolean onNpu, File model, int threads)
            throws IOException {
        this.environment = environment;
        this.session = session;
        this.onNpu = onNpu;
        this.model = model;
        this.threads = threads;
        try {
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
            output = session.getOutputNames().iterator().next();
            trim = nFft / 2;
            size = hop * (dimT - 1);
            step = size - 2 * trim;
            margin = RATE;
            forward = new MdxStft(nFft, hop);
            inverse = new MdxStft(nFft, hop);
            inputs = new float[2][4 * dimF * dimT];
            outputs = new float[2][4 * dimF * dimT];
            int cells = forward.frames(size) * forward.bins();
            spectrumIn = new float[2][cells];
            spectrumOut = new float[2][cells];
            long[] shape = {1, 4, dimF, dimT};
            inputBuffer = direct(4 * dimF * dimT);
            outputBuffer = direct(4 * dimF * dimT);
            inputTensor = OnnxTensor.createTensor(environment, inputBuffer, shape);
            outputTensor = OnnxTensor.createTensor(environment, outputBuffer, shape);
        } catch (OrtException ex) {
            helpers.shutdown();
            throw new IOException("The separation model could not be read: " + ex.getMessage(), ex);
        } catch (IOException | RuntimeException ex) {
            helpers.shutdown();
            throw ex;
        }
    }

    private static OrtSession openOnCores(OrtEnvironment environment, File model, int threads) throws IOException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            // Without ONNX Runtime's arena and preplanned buffers the peak memory is half (1.3 GB instead of 2.6 GB
            // on a computer) and nothing stays held between segments; the speed is about the same.
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            OrtSession session = environment.createSession(model.getAbsolutePath(), options);
            Logger.printInfo(() -> "Transcribe: the voice is separated on " + threads + " cores");
            return session;
        } catch (OrtException ex) {
            throw new IOException("The separation model could not be loaded: " + ex.getMessage(), ex);
        }
    }

    private static FloatBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
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
        // The spectrum of segment k + 1 is made and the voice of segment k - 1 is restored while the model works on k.
        Future<?> next = helpers.submit(() -> prepare(padded0, padded1, 0, inputs[0]));
        Future<?> restoring = null;
        for (int segment = 0; segment < segments; segment++) {
            await(next);
            if (segment + 1 < segments) {
                int following = segment + 1;
                next = helpers.submit(() -> prepare(padded0, padded1, following * step, inputs[following % 2]));
            }
            float[] y = outputs[segment % 2];
            run(inputs[segment % 2], y);
            await(restoring);
            int from = segment * step;
            restoring = helpers.submit(() -> restore(y, voice0, voice1, from));
        }
        await(restoring);
        int start = first ? 0 : margin;
        int end = voice0.length - pad - (last ? 0 : margin);
        return new float[][]{
                java.util.Arrays.copyOfRange(voice0, start, end),
                java.util.Arrays.copyOfRange(voice1, start, end)};
    }

    /** The model's input for the segment that starts at {@code from}: both channels, one after the other. */
    private void prepare(float[] padded0, float[] padded1, int from, float[] x) {
        int bins = forward.bins();
        float[] re = spectrumIn[0];
        float[] im = spectrumIn[1];
        forward.forward(padded0, from, size, re, im);
        pack(re, x, 0, bins);
        pack(im, x, 1, bins);
        forward.forward(padded1, from, size, re, im);
        pack(re, x, 2, bins);
        pack(im, x, 3, bins);
    }

    /** The voice of the model's output for the segment that starts at {@code from}. */
    private void restore(float[] y, float[] voice0, float[] voice1, int from) {
        int bins = inverse.bins();
        int frames = inverse.frames(size);
        float[] re = spectrumOut[0];
        float[] im = spectrumOut[1];
        unpack(y, re, 0, bins);
        unpack(y, im, 1, bins);
        System.arraycopy(inverse.inverse(re, im, frames), trim, voice0, from, step);
        unpack(y, re, 2, bins);
        unpack(y, im, 3, bins);
        System.arraycopy(inverse.inverse(re, im, frames), trim, voice1, from, step);
    }

    /** Waits for a helper's work; its failure is this thread's. */
    private static void await(Future<?> work) throws IOException {
        if (work == null) return;
        try {
            work.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof OutOfMemoryError) throw (OutOfMemoryError) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException(cause);
        }
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

    /** The model's output for the input {@code x}, into {@code y}. */
    private void run(float[] x, float[] y) throws IOException {
        try {
            runOnce(x, y);
        } catch (OrtException ex) {
            if (!onNpu) throw new IOException("The separation model failed: " + ex.getMessage(), ex);
            Logger.printInfo(() -> "Transcribe: the neural processor failed, the cores go on", ex);
            closeQuietly(session);
            onNpu = false;
            session = openOnCores(environment, model, threads);
            try {
                runOnce(x, y);
            } catch (OrtException again) {
                throw new IOException("The separation model failed: " + again.getMessage(), again);
            }
        }
    }

    private void runOnce(float[] x, float[] y) throws OrtException {
        inputBuffer.clear();
        inputBuffer.put(x);
        // The output tensor is the caller's: the result does not close it.
        //noinspection EmptyTryBlock
        try (OrtSession.Result ignored = session.run(Collections.singletonMap(input, inputTensor),
                Collections.singletonMap(output, outputTensor))) {
        }
        outputBuffer.clear();
        outputBuffer.get(y);
    }

    private static void closeQuietly(OrtSession session) {
        try {
            if (session != null) session.close();
        } catch (OrtException ignored) {
            // Nothing more to free.
        }
    }

    @Override
    public void close() {
        helpers.shutdownNow();
        closeQuietly(session);
        inputTensor.close();
        outputTensor.close();
    }
}
