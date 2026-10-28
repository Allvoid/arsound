package app.revanced.extension.soundcloud.region;

/**
 * The shape of the wiggle that calls for a tap: a few quick turns to both sides that die out, then a pause. Pure
 * numbers, so the badge only has to apply them.
 */
final class ShakeCurve {
    /** How long one wiggle takes. */
    static final long DURATION_MS = 720;
    /** How often it repeats; all badges on screen wiggle together at multiples of it. */
    static final long PERIOD_MS = 4200;
    static final float MAX_DEGREES = 16f;
    /** Sideways travel at the strongest turn, in dp. */
    static final float MAX_SHIFT_DP = 2f;
    private static final float TURNS = 3f;

    private ShakeCurve() {
    }

    private static float envelope(float progress) {
        float left = 1f - progress;
        return left * left;
    }

    /** The turn in degrees at the progress of the wiggle, 0 at its start and at its end. */
    static float degrees(float progress) {
        if (progress <= 0f || progress >= 1f) return 0f;
        return MAX_DEGREES * envelope(progress) * (float) Math.sin(progress * TURNS * 2.0 * Math.PI);
    }

    /** The sideways shift in dp at the progress of the wiggle, 0 at its start and at its end. */
    static float shiftDp(float progress) {
        if (progress <= 0f || progress >= 1f) return 0f;
        return MAX_SHIFT_DP * envelope(progress) * (float) Math.sin(progress * TURNS * 2.0 * Math.PI);
    }

    /** The wait until the next multiple of the period; a whole period when the clock is on one. */
    static long delayUntilNext(long now) {
        long into = Math.floorMod(now, PERIOD_MS);
        return PERIOD_MS - into;
    }
}
