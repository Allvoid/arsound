package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.IOException;

/**
 * One loaded GigaAM v3 (Sber's Russian speech model, CTC head, in sherpa-onnx's NeMo form). It knows only Russian and
 * does not loop like Whisper, so the language is not looked for. On the voice of Russian songs it made the fewest
 * mistakes of all the models we tried (29.10): about four words in ten wrong against six and more for Whisper.
 * <p>
 * Created by {@link TranscriptionJob} for {@link AsrModel#GIGAAM}; the library must be loaded first ({@link NativeLibs}).
 */
final class GigaAmRunner implements Recognizer {
    private OfflineRecognizer recognizer;

    /** @throws IOException If the model cannot be loaded. */
    GigaAmRunner(Context context, AsrModel model, int threads) throws IOException {
        File folder = AsrInstaller.modelDirectory(context, model);
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setNemo(OfflineNemoEncDecCtcModelConfig.builder()
                        .setModel(new File(folder, model.modelName()).getAbsolutePath())
                        .build())
                .setTokens(new File(folder, model.tokensName()).getAbsolutePath())
                .setNumThreads(threads)
                .setProvider("cpu")
                .setDebug(false)
                .build();
        try {
            recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(modelConfig).build());
        } catch (RuntimeException | UnsatisfiedLinkError ex) {
            throw new IOException("The model could not be loaded: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Heard hear(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, Chunker.RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            String text = result == null || result.getText() == null ? "" : result.getText().trim();
            return new Heard(text, "ru");
        } finally {
            stream.release();
        }
    }

    @Override
    public void fixLanguage(String language) {
        // Russian only.
    }

    @Override
    public void close() {
        if (recognizer != null) recognizer.release();
        recognizer = null;
    }
}
