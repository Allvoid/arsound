package app.revanced.extension.soundcloud.player.dsp;

/**
 * One second-order filter section (a biquad) with the coefficients of Robert Bristow-Johnson's "Audio EQ Cookbook": a
 * peaking band or a low shelf. The coefficients are for one sample rate; the state is kept per channel, in the transposed
 * direct form II. No Android here, so it can be tested on the JVM.
 */
public final class Biquad {
    private double b0 = 1, b1, b2, a1, a2;
    private double[] z1 = new double[0];
    private double[] z2 = new double[0];

    /** The state is made for this many channels and cleared. */
    public void setChannels(int channels) {
        z1 = new double[channels];
        z2 = new double[channels];
    }

    public void clear() {
        java.util.Arrays.fill(z1, 0);
        java.util.Arrays.fill(z2, 0);
    }

    /**
     * A peaking band: lifts or cuts around a centre by a gain, one octave wide at its half gain.
     *
     * @return False if the band cannot be made at this sample rate (its centre is too near the top): the filter then passes the sound as it is.
     */
    public boolean peaking(double centerHz, double gainDb, double octaves, double sampleRate) {
        if (!usable(centerHz, sampleRate)) {
            passThrough();
            return false;
        }
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * centerHz / sampleRate;
        double sin = Math.sin(w0), cos = Math.cos(w0);
        double alpha = sin * Math.sinh(Math.log(2) / 2 * octaves * w0 / sin);
        set(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
        return true;
    }

    private static boolean usable(double hz, double sampleRate) {
        return sampleRate > 0 && hz > 0 && hz < sampleRate * 0.45;
    }

    public void passThrough() {
        b0 = 1;
        b1 = b2 = a1 = a2 = 0;
    }

    private void set(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }

    /** One sample of a channel through the filter. */
    public double process(int channel, double x) {
        double y = b0 * x + z1[channel];
        z1[channel] = b1 * x - a1 * y + z2[channel];
        z2[channel] = b2 * x - a2 * y;
        return y;
    }

    /** The gain of the filter at a frequency, as a factor (1 is no change). */
    public double magnitudeAt(double hz, double sampleRate) {
        double w = 2 * Math.PI * hz / sampleRate;
        double cos1 = Math.cos(w), sin1 = Math.sin(w), cos2 = Math.cos(2 * w), sin2 = Math.sin(2 * w);
        double numRe = b0 + b1 * cos1 + b2 * cos2, numIm = -(b1 * sin1 + b2 * sin2);
        double denRe = 1 + a1 * cos1 + a2 * cos2, denIm = -(a1 * sin1 + a2 * sin2);
        return Math.hypot(numRe, numIm) / Math.hypot(denRe, denIm);
    }
}
