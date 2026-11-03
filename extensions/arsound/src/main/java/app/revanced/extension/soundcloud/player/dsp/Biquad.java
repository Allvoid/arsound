package app.revanced.extension.soundcloud.player.dsp;

/**
 * One second-order filter section (a biquad) with the coefficients of Robert Bristow-Johnson's "Audio EQ Cookbook": a
 * peaking band or a low shelf. The coefficients are for one sample rate; the state is kept per channel, in the transposed
 * direct form II. The coefficients can be set at once, or glided to those of another design over a few milliseconds
 * ({@link #glideTo}), so dragging a slider does not click. No Android here, so it can be tested on the JVM.
 */
public final class Biquad {
    /** The state counts as dead under this size (about -120 dB, below one step of 16-bit sound). */
    private static final double QUIET = 1e-6;

    private double b0 = 1, b1, b2, a1, a2;
    /** Where a glide is going, and how far each coefficient moves in one frame. */
    private double tb0 = 1, tb1, tb2, ta1, ta2;
    private double db0, db1, db2, da1, da2;
    private int glideLeft;
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
     * A peaking band: lifts or cuts around a centre by a gain, one octave wide at its half gain. The coefficients are
     * taken at once.
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

    /** A low shelf: lifts or cuts everything under the corner by a gain (shelf slope 1). The coefficients are taken at once. */
    public boolean lowShelf(double cornerHz, double gainDb, double sampleRate) {
        if (!usable(cornerHz, sampleRate)) {
            passThrough();
            return false;
        }
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * cornerHz / sampleRate;
        double sin = Math.sin(w0), cos = Math.cos(w0);
        double alpha = sin / 2 * Math.sqrt(2);
        double root = 2 * Math.sqrt(a) * alpha;
        set(a * ((a + 1) - (a - 1) * cos + root),
                2 * a * ((a - 1) - (a + 1) * cos),
                a * ((a + 1) - (a - 1) * cos - root),
                (a + 1) + (a - 1) * cos + root,
                -2 * ((a - 1) + (a + 1) * cos),
                (a + 1) + (a - 1) * cos - root);
        return true;
    }

    private static boolean usable(double hz, double sampleRate) {
        return sampleRate > 0 && hz > 0 && hz < sampleRate * 0.45;
    }

    public void passThrough() {
        b0 = 1;
        b1 = b2 = a1 = a2 = 0;
        glideLeft = 0;
    }

    private void set(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
        glideLeft = 0;
    }

    /** Takes the coefficients of another filter at once (the state stays) and drops any glide that was going. */
    public void copyCoefficients(Biquad other) {
        b0 = other.b0;
        b1 = other.b1;
        b2 = other.b2;
        a1 = other.a1;
        a2 = other.a2;
        glideLeft = 0;
    }

    /**
     * Goes from the coefficients of now to those of another filter in this many frames, a straight line for each
     * coefficient; with no frames, at once. The two designs are stable, and the stable pairs (a1, a2) form a triangle,
     * which has no dent, so every step on the line is stable too. The other filter is only read: it can be a scratch one
     * the design was worked out in. Call {@link #advance} once per frame to move along.
     */
    public void glideTo(Biquad other, int frames) {
        if (frames <= 0) {
            copyCoefficients(other);
            return;
        }
        tb0 = other.b0;
        tb1 = other.b1;
        tb2 = other.b2;
        ta1 = other.a1;
        ta2 = other.a2;
        db0 = (tb0 - b0) / frames;
        db1 = (tb1 - b1) / frames;
        db2 = (tb2 - b2) / frames;
        da1 = (ta1 - a1) / frames;
        da2 = (ta2 - a2) / frames;
        glideLeft = frames;
    }

    /** One frame along a glide: call it once per frame, before the channels of that frame. Costs one check when no glide is going. */
    public void advance() {
        if (glideLeft == 0) return;
        if (--glideLeft == 0) {
            // The last step lands on the target exactly, so the rounding of the sums never leaves it a little off.
            b0 = tb0;
            b1 = tb1;
            b2 = tb2;
            a1 = ta1;
            a2 = ta2;
        } else {
            b0 += db0;
            b1 += db1;
            b2 += db2;
            a1 += da1;
            a2 += da2;
        }
    }

    public boolean isGliding() {
        return glideLeft > 0;
    }

    /**
     * Whether the state of every channel is next to nothing. A flat filter (numerator equal to denominator) passes the
     * sound as it is exactly when its state is empty; what is left in it dies away on its own, and this tells when.
     */
    public boolean isQuiet() {
        for (int i = 0; i < z1.length; i++) {
            if (Math.abs(z1[i]) > QUIET || Math.abs(z2[i]) > QUIET) return false;
        }
        return true;
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
