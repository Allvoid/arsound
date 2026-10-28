package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import app.revanced.extension.shared.Logger;

/**
 * One loaded Whisper model, listening to windows of a song one after another. It is the only class that touches the
 * sherpa-onnx classes, so the rest of the code does not depend on them. The model takes a gigabyte of memory, so one
 * runner lives for one song and is {@link #close closed} at its end.
 * <p>
 * Created and driven by {@link TranscriptionJob}; the library must be loaded first ({@link NativeLibs}).
 */
final class WhisperRunner implements AutoCloseable {
    /** What the model heard in one window. */
    static final class Heard {
        final String text;
        /** The language Whisper recognised, in its short form ("ru"); empty if it did not say. */
        final String language;

        Heard(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    /**
     * Frames of silence (10 ms each) added after the sound of a window. The library's default is 1000, ten seconds of
     * nothing after the last word of a short window, which is where Whisper starts to make words up; the authors of
     * sherpa-onnx suggest 300 for the multilingual models. A full window of 24 to 30 seconds is not affected.
     * The decoder has no other setting for songs in this version (no limit of tokens, no repetition penalty).
     */
    private static final int TAIL_PADDINGS = 300;

    private final File folder;
    private final AsrModel model;
    private final int threads;
    private OfflineRecognizer recognizer;

    /**
     * @param language "" lets Whisper find the language of every window itself; a code ("ru") fixes it.
     * @throws IOException If the model cannot be loaded.
     */
    WhisperRunner(Context context, AsrModel model, String language, int threads) throws IOException {
        this.folder = AsrInstaller.modelDirectory(context, model);
        this.model = model;
        this.threads = threads;
        recognizer = create(language);
    }

    private OfflineRecognizer create(String language) throws IOException {
        try {
            return new OfflineRecognizer(config(language));
        } catch (RuntimeException | UnsatisfiedLinkError ex) {
            throw new IOException("The model could not be loaded: " + ex.getMessage(), ex);
        }
    }

    private OfflineRecognizerConfig config(String language) {
        OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder()
                .setEncoder(new File(folder, model.encoderName()).getAbsolutePath())
                .setDecoder(new File(folder, model.decoderName()).getAbsolutePath())
                .setLanguage(language)
                .setTask("transcribe")
                .setTailPaddings(TAIL_PADDINGS)
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setWhisper(whisper)
                .setTokens(new File(folder, model.tokensName()).getAbsolutePath())
                .setNumThreads(threads)
                .setProvider("cpu")
                .setDebug(false)
                .build();
        return OfflineRecognizerConfig.builder().setOfflineModelConfig(modelConfig).build();
    }

    /** Fixes the language for the windows to come: Whisper is more accurate when it is told than when it guesses. */
    void fixLanguage(String language) throws IOException {
        try {
            recognizer.setConfig(config(language));
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Transcribe: the language could not be changed in place, loading again: " + ex);
            recognizer.release();
            recognizer = create(language);
        }
    }

    /** Listens to 16 kHz mono samples (at most 30 seconds) and writes down what is sung. */
    Heard hear(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, Chunker.RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            String text = result == null || result.getText() == null ? "" : result.getText().trim();
            return new Heard(text, shortLanguage(result == null ? null : result.getLang()));
        } finally {
            stream.release();
        }
    }

    /** "&lt;|ru|&gt;" and "ru" both become "ru". */
    static String shortLanguage(String raw) {
        if (raw == null) return "";
        String code = raw.replace("<|", "").replace("|>", "").trim().toLowerCase(Locale.ROOT);
        return code.matches("[a-z]{2,3}") ? code : "";
    }

    @Override
    public void close() {
        if (recognizer != null) recognizer.release();
        recognizer = null;
    }
}
