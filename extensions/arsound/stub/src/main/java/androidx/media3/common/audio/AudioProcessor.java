package androidx.media3.common.audio;

import java.nio.ByteBuffer;

/**
 * Media3's {@code AudioProcessor} as SoundCloud's build has it after shrinking: the methods have short names. The real
 * names of this build: a = isEnded, b = queueInput, c = queueEndOfStream, d = getOutput, f = configure.
 */
public interface AudioProcessor {
    /** isEnded. */
    boolean a();

    /** queueInput. */
    void b(ByteBuffer input);

    /** queueEndOfStream. */
    void c();

    /** getOutput. */
    ByteBuffer d();

    /** configure. */
    AudioFormat f(AudioFormat inputFormat) throws Exception;

    /** The real one throws unless an implementation overrides it. */
    default void flush() {
    }

    boolean isActive();

    void reset();

    /** The format of a stream: a = sample rate, b = channel count, c = encoding. */
    final class AudioFormat {
        public static final AudioFormat e = new AudioFormat(-1, -1, -1);

        public final int a;
        public final int b;
        public final int c;

        public AudioFormat(int sampleRate, int channelCount, int encoding) {
            a = sampleRate;
            b = channelCount;
            c = encoding;
        }
    }
}
