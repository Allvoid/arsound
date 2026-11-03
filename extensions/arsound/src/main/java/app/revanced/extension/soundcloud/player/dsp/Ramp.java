package app.revanced.extension.soundcloud.player.dsp;

/**
 * One number that glides to a new value in a straight line over a count of frames, instead of jumping, so a gain that
 * changes while the sound plays makes no click. It holds the value, the target and the step in plain fields: nothing is
 * allocated while it runs. Not thread safe: it belongs to the audio thread. No Android here, so it can be tested on the JVM.
 */
final class Ramp {
    private double value;
    private double target;
    private double step;
    private int left;

    Ramp(double initial) {
        value = target = initial;
    }

    /** Jumps to a value at once and drops any glide that was going. */
    void snap(double newValue) {
        value = target = newValue;
        step = 0;
        left = 0;
    }

    /** Goes from the value of now to a new one in this many frames; with none, it jumps. */
    void glideTo(double newTarget, int frames) {
        if (frames <= 0) {
            snap(newTarget);
            return;
        }
        target = newTarget;
        step = (newTarget - value) / frames;
        left = frames;
    }

    /** One frame on; the value after the step. Costs next to nothing when no glide is going. */
    double next() {
        if (left > 0) {
            // The last step lands on the target exactly, so the rounding of the sum never leaves it a little off.
            value = --left == 0 ? target : value + step;
        }
        return value;
    }

    double value() {
        return value;
    }

    boolean isGliding() {
        return left > 0;
    }
}
