package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * Cuts a song into the windows Whisper listens to, and measures how loud they are. Whisper takes 30 seconds at a
 * time and does the same work for a short piece as for a full one, so the windows are as long as they can be, and a
 * window ends in the quietest moment near its limit so that a word is not cut in two.
 * <p>
 * Pure arithmetic on 16 kHz mono samples; used by {@link TranscriptionJob} and {@link LineTimer}.
 */
final class Chunker {
    static final int RATE = 16_000;
    /** The longest piece Whisper can listen to. */
    static final int MAX_WINDOW = 30 * RATE;
    /** A window that has to be cut ends somewhere after this, in the quietest place. */
    private static final int CUT_FROM = 24 * RATE;
    private static final int FRAME = RATE / 5;
    private static final int HOP = RATE / 10;
    /** Below this level (about -54 dBFS) a window holds nothing to recognise: not even a quiet voice. */
    private static final float SILENT_RMS = 0.002f;

    private Chunker() {
    }

    /**
     * Where the window that starts at {@code start} ends: the end of the song if it fits in one window, else the
     * middle of the quietest 200 ms between 24 and 30 seconds, preferring the later of two equally quiet places.
     */
    static int windowEnd(float[] pcm, int start) {
        return windowEnd(pcm, pcm.length, start);
    }

    /** The same for the first {@code length} samples of the array. */
    static int windowEnd(float[] pcm, int length, int start) {
        if (length - start <= MAX_WINDOW) return length;
        int bestCentre = start + MAX_WINDOW;
        double best = Double.MAX_VALUE;
        for (int from = start + CUT_FROM; from + FRAME <= start + MAX_WINDOW; from += HOP) {
            double level = rms(pcm, from, from + FRAME);
            if (level <= best) {
                best = level;
                bestCentre = from + FRAME / 2;
            }
        }
        return bestCentre;
    }

    /**
     * Where to cut a window in two: the middle of the quietest 200 ms between 40 and 60 percent of it, so that a
     * word is not cut in two. Used to listen again, in halves, to a window in which the decoder fell into a loop.
     */
    static int splitPoint(float[] pcm, int start, int end) {
        int length = end - start;
        int from = start + length * 2 / 5;
        int to = start + length * 3 / 5;
        int centre = start + length / 2;
        double best = Double.MAX_VALUE;
        for (int at = from; at + FRAME <= to; at += HOP) {
            double level = rms(pcm, at, at + FRAME);
            if (level < best) {
                best = level;
                centre = at + FRAME / 2;
            }
        }
        return centre;
    }

    /** Whether the piece is so quiet that Whisper would only make words up for it. */
    static boolean isSilent(float[] pcm, int from, int to) {
        return rms(pcm, from, to) < SILENT_RMS;
    }

    /**
     * Where the sound is in a piece: from the first loud 100 ms to the last one, where "loud" is a fifth of the
     * piece's own loud level. The words are somewhere in between, which is all the timing of the lines needs.
     *
     * @return {first sample, end sample}; the whole piece if it is evenly loud or too short to tell.
     */
    static int[] activeSpan(float[] pcm, int from, int to) {
        int frames = (to - from) / HOP;
        if (frames < 4) return new int[]{from, to};
        double[] levels = new double[frames];
        double top = 0;
        for (int i = 0; i < frames; i++) {
            levels[i] = rms(pcm, from + i * HOP, from + (i + 1) * HOP);
            top = Math.max(top, levels[i]);
        }
        // The loud level is a high percentile, so one click does not set it.
        double[] sorted = levels.clone();
        java.util.Arrays.sort(sorted);
        double loud = sorted[Math.min(frames - 1, (int) (frames * 0.9))];
        double threshold = Math.max(loud * 0.2, SILENT_RMS);
        int first = -1;
        int last = -1;
        for (int i = 0; i < frames; i++) {
            if (levels[i] >= threshold) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return new int[]{from, to};
        return new int[]{from + first * HOP, Math.min(to, from + (last + 1) * HOP)};
    }

    /** The moment of a sample in milliseconds from the start of the song. */
    static long toMs(int sample) {
        return sample * 1000L / RATE;
    }

    static double rms(float[] pcm, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(pcm.length, to);
        if (end <= start) return 0;
        double sum = 0;
        for (int i = start; i < end; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (end - start));
    }
}
