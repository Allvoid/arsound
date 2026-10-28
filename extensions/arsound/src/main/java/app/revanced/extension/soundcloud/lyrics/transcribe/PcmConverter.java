package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * Turns the PCM a decoder hands out (interleaved channels, 16-bit integers or floats) into mono floats in the range
 * -1 to 1, by averaging the channels. Pure arithmetic; used by {@link AudioDecoder}.
 */
final class PcmConverter {
    private PcmConverter() {
    }

    /**
     * @param interleaved Frames of {@code channels} samples each.
     * @param frames      How many frames to convert.
     * @param mono        Receives {@code frames} samples.
     */
    static void monoFromShorts(short[] interleaved, int frames, int channels, float[] mono) {
        float scale = 1f / (32768f * channels);
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            int base = frame * channels;
            for (int c = 0; c < channels; c++) sum += interleaved[base + c];
            mono[frame] = sum * scale;
        }
    }

    static void monoFromFloats(float[] interleaved, int frames, int channels, float[] mono) {
        float scale = 1f / channels;
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0f;
            int base = frame * channels;
            for (int c = 0; c < channels; c++) sum += interleaved[base + c];
            mono[frame] = sum * scale;
        }
    }
}
