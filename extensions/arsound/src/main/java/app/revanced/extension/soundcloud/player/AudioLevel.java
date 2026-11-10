package app.revanced.extension.soundcloud.player;

import android.os.SystemClock;

import java.nio.ByteBuffer;

/**
 * How loud the music is right now, read from the audio the player's own sound chain
 * ({@link SoftEqualizerProcessor}) passes on, for the moving lines of the "My Wave" tab. The processor offers every
 * buffer; only a sparse sample of its values is looked at, so the player's thread does almost no work and nothing is
 * allocated. The value is the loudness of the last buffers, 0 to 1, falling a little with each quiet buffer.
 */
public final class AudioLevel {
    /** A value older than this is not the music of now (paused, or the chain does not run). */
    private static final long STALE_MS = 500;
    /** One value out of this many is looked at. */
    private static final int STRIDE = 16;
    /** Where a typical loud track lands: its loudness (RMS) times this reaches 1. */
    private static final float GAIN = 3.2f;
    /** How much of the level is kept from one buffer to the next when the new buffer is quieter. */
    private static final float FALL = 0.85f;

    private static volatile float level;
    private static volatile long stamp;

    private AudioLevel() {
    }

    /** Called by the sound chain with a buffer ready to go on; the buffer's position is left as it was. */
    static void feed(ByteBuffer samples, boolean floats) {
        float now = scale(rms(samples, floats));
        float previous = level;
        level = Math.max(now, previous * FALL);
        stamp = SystemClock.uptimeMillis();
    }

    /** @return The loudness of the music, 0 to 1, or -1 when no audio went through the chain lately. */
    public static float current() {
        if (SystemClock.uptimeMillis() - stamp > STALE_MS) return -1f;
        return level;
    }

    /** From a loudness (RMS, 0 to 1 of full scale) to the 0..1 the lines use. */
    static float scale(float rms) {
        return Math.max(0f, Math.min(1f, rms * GAIN));
    }

    /** The loudness (RMS, 0 to 1 of full scale) of every {@link #STRIDE}th value of the buffer; 0 for an empty one. */
    static float rms(ByteBuffer samples, boolean floats) {
        int width = floats ? 4 : 2;
        int start = samples.position();
        int count = samples.remaining() / width;
        if (count == 0) return 0f;
        double sum = 0;
        int used = 0;
        for (int i = 0; i < count; i += STRIDE) {
            float value = floats ? samples.getFloat(start + i * 4) : samples.getShort(start + i * 2) / 32768f;
            sum += value * value;
            used++;
        }
        return (float) Math.sqrt(sum / used);
    }
}
