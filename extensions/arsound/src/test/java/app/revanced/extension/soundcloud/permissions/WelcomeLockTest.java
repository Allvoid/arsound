package app.revanced.extension.soundcloud.permissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class WelcomeLockTest {
    @Before
    public void fresh() {
        WelcomeLock.reset();
    }

    @Test
    public void startsLockedForTenSeconds() {
        assertTrue(WelcomeLock.isLocked());
        assertEquals(10, WelcomeLock.secondsLeft());
        assertEquals(" (10)", WelcomeLock.suffix());
    }

    @Test
    public void roundsUpSoZeroNeverShowsWhileLocked() {
        WelcomeLock.spend(9_001);
        assertTrue(WelcomeLock.isLocked());
        assertEquals(1, WelcomeLock.secondsLeft());
    }

    @Test
    public void unlocksExactlyAfterTenSeconds() {
        WelcomeLock.spend(9_999);
        assertTrue(WelcomeLock.isLocked());
        WelcomeLock.spend(1);
        assertFalse(WelcomeLock.isLocked());
        assertEquals("", WelcomeLock.suffix());
    }

    @Test
    public void timeSpentInPiecesAddsUpAndNegativeTimeIsIgnored() {
        WelcomeLock.spend(4_000);
        WelcomeLock.spend(-5_000);
        WelcomeLock.spend(3_000);
        assertEquals(3, WelcomeLock.secondsLeft());
    }

    @Test
    public void staysUnlockedOnceFree() {
        WelcomeLock.spend(60_000);
        WelcomeLock.spend(1);
        assertFalse(WelcomeLock.isLocked());
        assertEquals(0, WelcomeLock.secondsLeft());
    }
}
