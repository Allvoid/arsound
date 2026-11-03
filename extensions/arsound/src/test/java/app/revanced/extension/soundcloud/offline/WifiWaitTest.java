package app.revanced.extension.soundcloud.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WifiWaitTest {
    private static final long NOW = 1_000_000;

    @Test
    public void unmeteredNetworkNeverWaitsForWifi() {
        assertFalse(WifiWait.waits(false, false, NOW, 0, 0));
        assertFalse(WifiWait.waits(false, false, NOW, NOW - 60_000, NOW - 60_000));
    }

    @Test
    public void workingDownloaderDoesNotWait() {
        // A queue downloading over mobile data on purpose: the tracks behind the running one wait for their turn.
        assertFalse(WifiWait.waits(true, true, NOW, 0, 0));
    }

    @Test
    public void idleDownloaderOnMobileDataWaits() {
        assertTrue(WifiWait.waits(true, false, NOW, 0, 0));
        assertTrue(WifiWait.waits(true, false, NOW, NOW - WifiWait.IDLE_MS - 1, NOW - 60_000));
    }

    @Test
    public void shortGapBetweenTwoTracksIsNotAWait() {
        // The last track finished a second ago and the next has not started yet.
        assertFalse(WifiWait.waits(true, false, NOW, NOW - 1_000, NOW - 120_000));
    }

    @Test
    public void freshRequestIsGivenTimeToStart() {
        assertFalse(WifiWait.waits(true, false, NOW, NOW - 120_000, NOW - 2_000));
    }

    @Test
    public void waitBeginsAfterTheIdleTime() {
        assertFalse(WifiWait.waits(true, false, NOW, NOW - WifiWait.IDLE_MS + 1, 0));
        assertTrue(WifiWait.waits(true, false, NOW, NOW - WifiWait.IDLE_MS, 0));
    }

    @Test
    public void laterOfWorkAndRequestCounts() {
        assertFalse(WifiWait.waits(true, false, NOW, NOW - 30_000, NOW - 5_000));
        assertFalse(WifiWait.waits(true, false, NOW, NOW - 5_000, NOW - 30_000));
        assertTrue(WifiWait.waits(true, false, NOW, NOW - 30_000, NOW - 20_000));
    }
}
