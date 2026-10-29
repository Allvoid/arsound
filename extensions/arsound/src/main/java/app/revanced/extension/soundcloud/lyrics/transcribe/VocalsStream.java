package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;

/**
 * Feeds a song to the voice separation a piece at a time, as it is decoded, so the whole song is never held at the
 * model's rate in stereo (ten minutes would be 200 MB). Pieces of {@code chunk} samples are separated with
 * {@code margin} samples of the neighbours on each side, like sherpa-onnx's {@code SplitIntoChunks}; the voice of each
 * piece, both channels averaged, goes to the output in order. Pure logic; the separation itself is {@link Chunks}.
 * <p>
 * Used by {@link VocalsTarget}; tested on its own.
 */
final class VocalsStream {
    /** Separates one piece: the voice of both channels, as long as the piece without its margins. */
    interface Chunks {
        float[][] separate(float[] left, float[] right, boolean first, boolean last) throws IOException;
    }

    /** Receives the voice, mono, in order. */
    interface Output {
        void voice(float[] mono, int count) throws IOException;
    }

    private final int chunk;
    private final int margin;
    private final Chunks chunks;
    private final Output output;
    private final GrowableFloats left = new GrowableFloats(1 << 16);
    private final GrowableFloats right = new GrowableFloats(1 << 16);
    /** The song position of the first sample still held. */
    private long held;
    /** The song position where the next piece starts. */
    private long next;

    VocalsStream(int chunk, int margin, Chunks chunks, Output output) {
        this.chunk = chunk;
        this.margin = Math.min(margin, chunk);
        this.chunks = chunks;
        this.output = output;
    }

    /** The song position up to which samples were given. */
    long received() {
        return held + left.size();
    }

    /** The song position up to which the voice was given out. */
    long separated() {
        return next;
    }

    void push(float[] leftSamples, float[] rightSamples, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            left.add(leftSamples[i]);
            right.add(rightSamples[i]);
        }
        // A piece is known not to be the last once a sample after its right margin has come.
        while (received() > next + chunk + margin) separate(next + chunk + margin, false);
    }

    /** The end of the song: what is left goes as the last piece. */
    void finish() throws IOException {
        if (received() > next) separate(received(), true);
    }

    private void separate(long end, boolean last) throws IOException {
        long start = Math.max(0, next - margin);
        int from = (int) (start - held);
        int length = (int) (end - start);
        float[] pieceLeft = new float[length];
        float[] pieceRight = new float[length];
        System.arraycopy(left.array(), from, pieceLeft, 0, length);
        System.arraycopy(right.array(), from, pieceRight, 0, length);
        float[][] voice = chunks.separate(pieceLeft, pieceRight, next == 0, last);
        int count = voice[0].length;
        float[] mono = new float[count];
        for (int i = 0; i < count; i++) mono[i] = 0.5f * (voice[0][i] + voice[1][i]);
        output.voice(mono, count);
        next = last ? received() : next + chunk;
        // The next piece starts a margin before its own start.
        int drop = (int) (Math.max(0, next - margin) - held);
        if (drop > 0) {
            left.dropFirst(drop);
            right.dropFirst(drop);
            held += drop;
        }
    }
}
