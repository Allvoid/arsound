package app.revanced.extension.soundcloud.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShakeCurveTest {
    @Test
    public void startsAndEndsAtRest() {
        assertEquals(0f, ShakeCurve.degrees(0f), 0f);
        assertEquals(0f, ShakeCurve.degrees(1f), 0f);
        assertEquals(0f, ShakeCurve.shiftDp(0f), 0f);
        assertEquals(0f, ShakeCurve.shiftDp(1f), 0f);
        assertEquals(0f, ShakeCurve.degrees(-1f), 0f);
        assertEquals(0f, ShakeCurve.degrees(2f), 0f);
    }

    @Test
    public void staysWithinTheLimits() {
        boolean left = false;
        boolean right = false;
        for (int step = 0; step <= 1000; step++) {
            float progress = step / 1000f;
            float degrees = ShakeCurve.degrees(progress);
            float shift = ShakeCurve.shiftDp(progress);
            assertTrue(Math.abs(degrees) <= ShakeCurve.MAX_DEGREES);
            assertTrue(Math.abs(shift) <= ShakeCurve.MAX_SHIFT_DP);
            left |= degrees < -1f;
            right |= degrees > 1f;
        }
        assertTrue("turns both ways", left && right);
    }

    @Test
    public void diesOut() {
        float early = 0f;
        float late = 0f;
        for (int step = 0; step <= 1000; step++) {
            float progress = step / 1000f;
            float degrees = Math.abs(ShakeCurve.degrees(progress));
            if (progress < .4f) early = Math.max(early, degrees);
            if (progress > .8f) late = Math.max(late, degrees);
        }
        assertTrue(late < early / 3f);
    }

    @Test
    public void waitsForTheNextMultipleOfThePeriod() {
        assertEquals(ShakeCurve.PERIOD_MS, ShakeCurve.delayUntilNext(0));
        assertEquals(ShakeCurve.PERIOD_MS, ShakeCurve.delayUntilNext(ShakeCurve.PERIOD_MS * 7));
        assertEquals(1, ShakeCurve.delayUntilNext(ShakeCurve.PERIOD_MS - 1));
        assertEquals(ShakeCurve.PERIOD_MS - 100, ShakeCurve.delayUntilNext(ShakeCurve.PERIOD_MS * 3 + 100));
    }
}
