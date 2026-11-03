package app.revanced.extension.soundcloud.player.dsp;

/**
 * A reverb after Schroeder: four comb filters with a little damping side by side, then two all-pass filters in a row,
 * for each channel on its own (each channel's delays differ a little, so a stereo sound gets some width). Light on the
 * processor: six short delay lines per channel. No Android here, so it can be tested on the JVM.
 */
public final class SoftReverb {
    /** Delays in samples at 44.1 kHz. */
    private static final int[] COMBS = {1116, 1188, 1277, 1356};
    private static final int[] ALLPASSES = {556, 441};
    private static final int SPREAD = 23;
    private static final float DAMPING = 0.25f;
    private static final float ALLPASS_FEEDBACK = 0.5f;

    private float[][][] combLines;
    private int[][] combPositions;
    private float[][] combDamped;
    private float[][][] allpassLines;
    private int[][] allpassPositions;
    private float feedback = 0.8f;

    /** Makes the delay lines for a sample rate and a number of channels, empty. */
    public void configure(int sampleRate, int channels) {
        combLines = new float[channels][COMBS.length][];
        combPositions = new int[channels][COMBS.length];
        combDamped = new float[channels][COMBS.length];
        allpassLines = new float[channels][ALLPASSES.length][];
        allpassPositions = new int[channels][ALLPASSES.length];
        double scale = sampleRate / 44100.0;
        for (int channel = 0; channel < channels; channel++) {
            for (int i = 0; i < COMBS.length; i++) {
                combLines[channel][i] = new float[Math.max(1, (int) Math.round((COMBS[i] + channel * SPREAD) * scale))];
            }
            for (int i = 0; i < ALLPASSES.length; i++) {
                allpassLines[channel][i] = new float[Math.max(1, (int) Math.round((ALLPASSES[i] + channel * SPREAD) * scale))];
            }
        }
    }

    /** How long the tail lasts: the feedback of the combs, 0 to just under 1. */
    public void setFeedback(float feedback) {
        this.feedback = Math.max(0f, Math.min(0.97f, feedback));
    }

    /** One sample of a channel in, the wet sound out (without the dry). */
    public float process(int channel, float input) {
        float sum = 0;
        for (int i = 0; i < COMBS.length; i++) {
            float[] line = combLines[channel][i];
            int position = combPositions[channel][i];
            float out = line[position];
            // The damping is a low-pass in the loop: the highs die faster than the lows, as in a room.
            combDamped[channel][i] = out * (1 - DAMPING) + combDamped[channel][i] * DAMPING;
            line[position] = input * 0.015f + combDamped[channel][i] * feedback;
            combPositions[channel][i] = position + 1 == line.length ? 0 : position + 1;
            sum += out;
        }
        for (int i = 0; i < ALLPASSES.length; i++) {
            float[] line = allpassLines[channel][i];
            int position = allpassPositions[channel][i];
            float buffered = line[position];
            float out = -sum + buffered;
            line[position] = sum + buffered * ALLPASS_FEEDBACK;
            allpassPositions[channel][i] = position + 1 == line.length ? 0 : position + 1;
            sum = out;
        }
        return sum;
    }

    public void clear() {
        if (combLines == null) return;
        for (float[][] channel : combLines) for (float[] line : channel) java.util.Arrays.fill(line, 0);
        for (float[][] channel : allpassLines) for (float[] line : channel) java.util.Arrays.fill(line, 0);
        for (float[] damped : combDamped) java.util.Arrays.fill(damped, 0);
    }
}
