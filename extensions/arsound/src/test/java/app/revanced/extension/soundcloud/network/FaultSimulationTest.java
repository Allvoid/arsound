package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FaultSimulationTest {
    @Test
    public void offlineFailsAtOnceWithoutWaiting() {
        FaultSimulation.Verdict verdict = FaultSimulation.decide(FaultSimulation.OFFLINE, 5_000, 0.9);
        assertNotNull(verdict.failure);
        assertEquals(0, verdict.delayMs);
    }

    @Test
    public void noModeOnlyAddsTheFreeDelay() {
        FaultSimulation.Verdict verdict = FaultSimulation.decide(FaultSimulation.NONE, 250, 0.0);
        assertNull(verdict.failure);
        assertEquals(250, verdict.delayMs);
    }

    @Test
    public void slowAddsItsDelayToTheFreeOne() {
        FaultSimulation.Verdict verdict = FaultSimulation.decide(FaultSimulation.SLOW, 200, 0.0);
        assertNull(verdict.failure);
        assertEquals(200 + FaultSimulation.SLOW_DELAY_MS, verdict.delayMs);
    }

    @Test
    public void badNetworkFailsAfterAWaitForTheLowRollsOnly() {
        FaultSimulation.Verdict failing = FaultSimulation.decide(FaultSimulation.BAD_NETWORK, 100, 0.29);
        assertNotNull(failing.failure);
        assertEquals(100 + FaultSimulation.BAD_NETWORK_DELAY_MS, failing.delayMs);

        FaultSimulation.Verdict passing = FaultSimulation.decide(FaultSimulation.BAD_NETWORK, 100, 0.31);
        assertNull(passing.failure);
        assertEquals(100, passing.delayMs);
    }

    @Test
    public void theFreeDelayStaysBetweenZeroAndAMinute() {
        assertEquals(0, FaultSimulation.decide(FaultSimulation.NONE, -5, 0.5).delayMs);
        assertEquals(60_000, FaultSimulation.decide(FaultSimulation.NONE, 9_999_999, 0.5).delayMs);
    }

    @Test
    public void anUnknownStoredModeCountsAsNone() {
        assertEquals(FaultSimulation.NONE, FaultSimulation.parse("typo"));
        assertEquals(FaultSimulation.NONE, FaultSimulation.parse(null));
        assertEquals(FaultSimulation.SLOW, FaultSimulation.parse("slow"));
        assertNull(FaultSimulation.decide("typo", 0, 0.0).failure);
    }
}
