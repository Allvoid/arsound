package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;

/**
 * Where {@link AudioDecoder} puts the decoded sound, and what comes out for the speech model: mono floats at 16 kHz.
 * The whole song as it is ({@link MonoTarget}), or only its voice ({@link VocalsTarget}).
 */
interface PcmTarget {
    /** The decoder's format, before the first samples. */
    void start(int rate, int channels) throws IOException;

    /** Interleaved samples in the range -1 to 1, {@code frames} frames of the channels given to {@link #start}. */
    void push(float[] interleaved, int frames) throws IOException;

    /** The end of the song. @return Mono samples at {@link Chunker#RATE}. */
    float[] finish() throws IOException;
}
