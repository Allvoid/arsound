package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;

/**
 * A loaded speech model, listening to windows of a song one after another: Whisper ({@link WhisperRunner}) or the
 * Russian GigaAM ({@link GigaAmRunner}). The models take hundreds of megabytes, so one lives for one song and is
 * {@link #close closed} at its end. Driven by {@link SongListener}.
 */
interface Recognizer extends AutoCloseable {
    /** What the model heard in one window. */
    final class Heard {
        final String text;
        /** The language the model recognised, in its short form ("ru"); empty if it did not say. */
        final String language;

        Heard(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    /** Listens to 16 kHz mono samples (at most 30 seconds) and writes down what is sung. */
    Heard hear(float[] samples);

    /** Fixes the language for the windows to come. A model that knows one language ignores it. */
    void fixLanguage(String language) throws IOException;

    @Override
    void close();
}
