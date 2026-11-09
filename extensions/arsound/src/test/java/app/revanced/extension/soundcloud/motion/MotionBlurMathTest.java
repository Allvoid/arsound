package app.revanced.extension.soundcloud.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionBlurMathTest {
    @Test
    public void slowSpeedsDoNotBlur() {
        assertEquals(0f, MotionBlurMath.intensity(0f), 0f);
        assertEquals(0f, MotionBlurMath.intensity(MotionBlurMath.QUIET_DP_PER_SECOND), 0f);
        assertEquals(0f, MotionBlurMath.intensity(-100f), 0f);
    }

    @Test
    public void intensityRisesToOneAndStays() {
        float last = 0f;
        for (float speed = MotionBlurMath.QUIET_DP_PER_SECOND; speed <= MotionBlurMath.FULL_DP_PER_SECOND; speed += 100f) {
            float now = MotionBlurMath.intensity(speed);
            assertTrue(now >= last);
            last = now;
        }
        assertEquals(1f, MotionBlurMath.intensity(MotionBlurMath.FULL_DP_PER_SECOND), 0f);
        assertEquals(1f, MotionBlurMath.intensity(20_000f), 0f);
    }

    @Test
    public void directionDoesNotMatter() {
        assertEquals(MotionBlurMath.intensity(2000f), MotionBlurMath.intensity(-2000f), 0f);
    }

    @Test
    public void nanIsNoBlur() {
        assertEquals(0f, MotionBlurMath.intensity(Float.NaN), 0f);
    }

    @Test
    public void strengthScalesTheRadius() {
        assertEquals(0f, MotionBlurMath.acrossDp(0, 1f), 0f);
        assertEquals(2f * MotionBlurMath.acrossDp(30, 1f), MotionBlurMath.acrossDp(60, 1f), 1e-4f);
        assertEquals(MotionBlurMath.acrossDp(100, 1f), MotionBlurMath.acrossDp(500, 1f), 0f);
    }

    @Test
    public void stretchLengthensTheBlurAlongTheMovement() {
        float across = MotionBlurMath.acrossDp(48, 1f);
        assertEquals(across, MotionBlurMath.alongDp(48, 100, 1f), 1e-4f);
        assertEquals(across * 2.04f, MotionBlurMath.alongDp(48, 204, 1f), 1e-3f);
        // Outside the slider the stretch is held to its ends.
        assertEquals(MotionBlurMath.alongDp(48, 400, 1f), MotionBlurMath.alongDp(48, 9000, 1f), 0f);
        assertEquals(across, MotionBlurMath.alongDp(48, -5, 1f), 1e-4f);
    }

    @Test
    public void quantizeDropsWhatCannotBeSeenAndRoundsToHalfPixels() {
        assertEquals(0f, MotionBlurMath.quantize(0.3f), 0f);
        assertEquals(0f, MotionBlurMath.quantize(Float.NaN), 0f);
        assertEquals(0.5f, MotionBlurMath.quantize(0.6f), 0f);
        assertEquals(3.5f, MotionBlurMath.quantize(3.6f), 0f);
        assertEquals(4f, MotionBlurMath.quantize(3.8f), 0f);
    }

    @Test
    public void aFlingHalvesEveryHalfLife() {
        assertEquals(500f, MotionBlurMath.decayed(1000f, 330, 330), 1e-2f);
        assertEquals(250f, MotionBlurMath.decayed(1000f, 660, 330), 1e-2f);
        assertEquals(1000f, MotionBlurMath.decayed(1000f, 0, 330), 0f);
    }

    @Test
    public void followingRisesFastAndFallsSlowly() {
        float rising = MotionBlurMath.follow(1000f, 3000f, 16, 150);
        assertTrue(rising > 2000f && rising < 3000f);
        float falling = MotionBlurMath.follow(3000f, 0f, 16, 150);
        assertTrue(falling > 2500f && falling < 3000f);
        // Never below the finger.
        assertEquals(2900f, MotionBlurMath.follow(3000f, 2900f, 100000, 150), 0f);
    }

    @Test
    public void theBlurAlongTheMovementHasACeiling() {
        assertEquals(MotionBlurMath.MAX_ALONG_DP, MotionBlurMath.alongDp(100, 400, 1f), 0f);
    }
}
