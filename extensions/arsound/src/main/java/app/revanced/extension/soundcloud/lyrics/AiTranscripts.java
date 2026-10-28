package app.revanced.extension.soundcloud.lyrics;

import app.revanced.extension.soundcloud.lyrics.transcribe.Transcript;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;

/**
 * The bridge between the recognised texts of {@code lyrics.transcribe} and the lyrics of this package: a transcript
 * becomes {@link Lyrics} marked as heard by the neural network. Used by {@link LyricsRepository} (a stored text in
 * place of "no lyrics") and {@link LyricsAi} (the text that has just been recognised).
 */
final class AiTranscripts {
    private AiTranscripts() {
    }

    /** The recognised text of the track if there is one and the recognition is on; null otherwise. */
    static Lyrics lookUp(TrackInfo info) {
        Transcript transcript = TranscriptionManager.stored(info.urn, info.durationMs);
        return transcript == null ? null : toLyrics(transcript);
    }

    static Lyrics toLyrics(Transcript transcript) {
        return new Lyrics(transcript.lines, transcript.times, Lyrics.Source.NEURAL);
    }
}
