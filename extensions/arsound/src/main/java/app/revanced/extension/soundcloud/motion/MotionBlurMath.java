package app.revanced.extension.soundcloud.motion;

/**
 * The numbers behind the motion blur: how fast a finger or a fling must go before anything blurs, how big the blur is
 * at a speed, how a fling dies away. Pure, so it runs in the JVM tests.
 */
final class MotionBlurMath {
    /** Below this speed (dp per second) nothing blurs: reading speed is sharp. */
    static final float QUIET_DP_PER_SECOND = 350f;
    /** From this speed on the blur is at its largest. */
    static final float FULL_DP_PER_SECOND = 4200f;
    /** The blur across the movement at full speed, in dp, for each point of the strength slider (100 points = 7 dp). */
    private static final float ACROSS_DP_PER_POINT = 0.07f;
    /** A radius under this (px) is no blur at all. */
    static final float MIN_RADIUS_PX = 0.5f;
    /** Radii are rounded to this step (px), so the blur object is made again only when the picture could change. */
    private static final float STEP_PX = 0.5f;

    /** The stretch slider, in hundredths: 1.00x (the same blur both ways) to 4.00x. */
    static final int STRETCH_MIN = 100;
    static final int STRETCH_MAX = 400;
    static final int STRETCH_DEFAULT = 204;
    static final int AMOUNT_DEFAULT = 48;

    private MotionBlurMath() {
    }

    /** How strongly a speed blurs, 0 to 1: nothing when quiet, a smooth rise, the top at {@link #FULL_DP_PER_SECOND}. */
    static float intensity(float dpPerSecond) {
        float t = (Math.abs(dpPerSecond) - QUIET_DP_PER_SECOND) / (FULL_DP_PER_SECOND - QUIET_DP_PER_SECOND);
        if (!(t > 0f)) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /** The blur across the movement, in dp, at the strength (0 to 100) and the intensity of the speed (0 to 1). */
    static float acrossDp(int amount, float intensity) {
        return Math.max(0, Math.min(100, amount)) * ACROSS_DP_PER_POINT * intensity;
    }

    /** No blur is longer than this (dp), whatever the sliders say: a bigger one costs the graphics chip more than it shows. */
    static final float MAX_ALONG_DP = 24f;

    /** The blur along the movement, in dp: the one across, stretched by {@code stretchHundredths} (204 is 2.04x). */
    static float alongDp(int amount, int stretchHundredths, float intensity) {
        int stretch = Math.max(STRETCH_MIN, Math.min(STRETCH_MAX, stretchHundredths));
        return Math.min(MAX_ALONG_DP, acrossDp(amount, intensity) * stretch / 100f);
    }

    /** A radius in pixels rounded to the step, or 0 when it is too small to see. */
    static float quantize(float radiusPx) {
        if (!(radiusPx >= MIN_RADIUS_PX)) return 0f;
        return Math.round(radiusPx / STEP_PX) * STEP_PX;
    }

    /** The speed after {@code dtMs}, when it halves every {@code halfLifeMs}. */
    static float decayed(float speed, long dtMs, long halfLifeMs) {
        if (dtMs <= 0) return speed;
        return (float) (speed * Math.pow(0.5, (double) dtMs / halfLifeMs));
    }

    /**
     * The speed shown while the finger is down: it rises to the finger's speed at once (most of the way in a frame), and
     * falls only as fast as a fling dies, so a hand that stops for a moment does not make the blur flicker.
     */
    static float follow(float shown, float fingerSpeed, long dtMs, long halfLifeMs) {
        if (fingerSpeed >= shown) return shown + (fingerSpeed - shown) * 0.6f;
        return Math.max(fingerSpeed, decayed(shown, dtMs, halfLifeMs));
    }
}
