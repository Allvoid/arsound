package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.Arrays;

/** A list of float samples that grows as the decoder produces them; used by {@link AudioDecoder} and {@link StreamResampler}. */
final class GrowableFloats {
    private float[] data;
    private int size;

    GrowableFloats(int capacity) {
        data = new float[Math.max(16, capacity)];
    }

    void add(float value) {
        if (size == data.length) data = Arrays.copyOf(data, (int) Math.min(Integer.MAX_VALUE - 8, data.length * 3L / 2 + 16));
        data[size++] = value;
    }

    int size() {
        return size;
    }

    float get(int index) {
        return data[index];
    }

    /** The samples themselves (more room than {@link #size}), for a loop that reads many; valid until the next add. */
    float[] array() {
        return data;
    }

    /** Drops the first {@code count} samples. */
    void dropFirst(int count) {
        if (count <= 0) return;
        if (count >= size) {
            size = 0;
            return;
        }
        int kept = size - count;
        System.arraycopy(data, count, data, 0, kept);
        size = kept;
    }

    float[] toArray() {
        return Arrays.copyOf(data, size);
    }
}
