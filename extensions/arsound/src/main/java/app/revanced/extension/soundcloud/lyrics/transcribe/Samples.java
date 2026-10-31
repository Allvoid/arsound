package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The decoded sound of a song for the speech model: the samples are the first {@code length} of {@code data}, which
 * may hold more. Handed over as it was filled, not copied to a buffer of the exact size: for a song of ten minutes a
 * copy was some 40 MB more on the Java heap at the moment it was largest.
 */
final class Samples {
    final float[] data;
    final int length;

    Samples(float[] data, int length) {
        this.data = data;
        this.length = length;
    }

    /** How many samples a song of this length takes at {@link Chunker#RATE}, with a little room. */
    static int expectedFor(long durationMs) {
        if (durationMs <= 0) return Chunker.RATE * 60;
        long samples = durationMs * Chunker.RATE / 1000;
        return (int) Math.min(Integer.MAX_VALUE - 8, samples + samples / 50 + Chunker.RATE);
    }
}
