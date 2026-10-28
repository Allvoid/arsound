package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * Turns a stream of mono samples at one rate into samples at another, a block at a time, keeping what it needs
 * between blocks. Windowed sinc with a low-pass at the lower of the two rates, so that 44.1 kHz music loses no
 * more than the band Whisper does not hear anyway and nothing folds back as noise. The filter is tabulated at 512
 * fractional positions, which keeps the cost at one multiplication per tap (a song is a few hundred million).
 * <p>
 * Used by {@link AudioDecoder}, which hands over the decoded PCM, and by the tests.
 */
final class StreamResampler {
    private static final int PHASES = 512;
    /** Zero crossings of the sinc kept on each side, counted at the lower rate. */
    private static final int LOBES = 8;

    private final double step;
    private final int half;
    private final float[][] table;
    private final GrowableFloats pending = new GrowableFloats(1 << 15);
    /** Position of the next output sample in the input, counted from the first sample still in {@link #pending}. */
    private double position;

    StreamResampler(int inputRate, int outputRate) {
        step = (double) inputRate / outputRate;
        double cutoff = Math.min(1.0, (double) outputRate / inputRate) * 0.97;
        half = (int) Math.ceil(LOBES / cutoff);
        table = new float[PHASES][2 * half];
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = (double) phase / PHASES;
            double sum = 0;
            for (int tap = 0; tap < 2 * half; tap++) {
                // Tap 'tap' sits at input offset (tap - half + 1) from the integer part of the position.
                double distance = (tap - half + 1) - fraction;
                double window = 0.5 + 0.5 * Math.cos(Math.PI * distance / half);
                double x = cutoff * distance;
                double sinc = Math.abs(x) < 1e-9 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double weight = Math.abs(distance) >= half ? 0 : sinc * window * cutoff;
                table[phase][tap] = (float) weight;
                sum += weight;
            }
            // The gain at zero frequency is exactly one for every phase.
            for (int tap = 0; tap < 2 * half; tap++) table[phase][tap] /= (float) sum;
        }
        // The first samples have nothing before them: the missing ones are silence.
        for (int i = 0; i < half; i++) pending.add(0f);
        position = half;
    }

    /** Takes a block of input and puts the output samples that can be worked out so far into {@code out}. */
    void push(float[] input, int count, GrowableFloats out) {
        for (int i = 0; i < count; i++) pending.add(input[i]);
        produce(out, false);
    }

    /** Ends the stream: the last samples are worked out with silence after them. */
    void finish(GrowableFloats out) {
        for (int i = 0; i < half; i++) pending.add(0f);
        produce(out, true);
    }

    private void produce(GrowableFloats out, boolean last) {
        int available = pending.size();
        float[] samples = pending.array();
        while (true) {
            int base = (int) position;
            // Taps run from base - half + 1 to base + half.
            if (base + half >= available) break;
            int phase = (int) ((position - base) * PHASES);
            float[] weights = table[phase];
            int from = base - half + 1;
            float sum = 0f;
            for (int tap = 0; tap < weights.length; tap++) sum += weights[tap] * samples[from + tap];
            out.add(sum);
            position += step;
        }
        // Samples that no later output will touch are dropped.
        int drop = (int) position - half;
        if (!last && drop > 0) {
            pending.dropFirst(drop);
            position -= drop;
        }
    }
}
