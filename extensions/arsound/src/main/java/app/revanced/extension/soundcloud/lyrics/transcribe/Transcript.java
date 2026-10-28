package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The words of one song as the neural network heard them: lines with the moment each starts at, the model and the
 * language that made them, and how far the work got. A finished transcript is shown as the lyrics of the song; a
 * partial one is what is left on the phone when the work is stopped, so the next try goes on from there.
 * <p>
 * Made by {@link TranscriptionJob}, kept by {@link TranscriptStore} (text form: {@link TranscriptCodec}), turned into
 * lyrics by {@code AiTranscripts} in the lyrics package.
 */
public final class Transcript {
    /** How far the work got. */
    public enum State {
        /** Stopped half way; goes on from {@link #resumeMs}. */
        PARTIAL("partial"),
        /** All windows were listened to and there are words. */
        DONE("done"),
        /** All windows were listened to and no words came out: not tried again with the same model. */
        FAILED("failed");

        final String word;

        State(String word) {
            this.word = word;
        }

        static State fromWord(String word) {
            for (State state : values()) {
                if (state.word.equals(word)) return state;
            }
            return null;
        }
    }

    public final String urn;
    public final String modelId;
    public final State state;
    /** The language the words were recognised in, as Whisper names it ("ru", "en"); empty if it was not found out. */
    public final String language;
    /** The length of the track as the player told it when the work started. */
    public final long durationMs;
    public final long createdAt;
    /** Windows of 30 seconds already listened to, and how many there are in all (an estimate until the end). */
    public final int windowsDone;
    public final int windowsTotal;
    /** Where the next window starts, in milliseconds of the song. */
    public final long resumeMs;
    public final String[] lines;
    /** The start of each line in milliseconds, ascending; same length as {@link #lines}. */
    public final long[] times;

    public Transcript(String urn, String modelId, State state, String language, long durationMs, long createdAt,
                      int windowsDone, int windowsTotal, long resumeMs, String[] lines, long[] times) {
        this.urn = urn;
        this.modelId = modelId;
        this.state = state;
        this.language = language == null ? "" : language;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
        this.windowsDone = windowsDone;
        this.windowsTotal = windowsTotal;
        this.resumeMs = resumeMs;
        this.lines = lines;
        this.times = times;
    }

    public boolean isDone() {
        return state == State.DONE;
    }
}
