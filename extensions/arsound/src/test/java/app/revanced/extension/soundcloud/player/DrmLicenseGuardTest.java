package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class DrmLicenseGuardTest {
    private static final byte[] LICENCE = {1, 2, 3, 4};
    private static final byte[] OTHER = {9, 8, 7};

    @Before
    public void forget() {
        DrmLicenseGuard.forgetAnswers();
    }

    @Test
    public void aLicenceThePhoneRestoresPassesThrough() {
        assertArrayEquals(LICENCE, DrmLicenseGuard.restorable(LICENCE, id -> {
        }));
        assertFalse(DrmLicenseGuard.storeLosesLicences());
    }

    @Test
    public void aLicenceMissingFromTheStoreIsDropped() {
        assertNull(DrmLicenseGuard.restorable(LICENCE, id -> {
            throw new IllegalArgumentException("{\"cdmError\":347}: BAD_VALUE");
        }));
    }

    @Test
    public void aDeadLicenceStopsSavingMore() {
        assertFalse(DrmLicenseGuard.storeLosesLicences());
        DrmLicenseGuard.restorable(LICENCE, id -> {
            throw new IllegalArgumentException("BAD_VALUE");
        });
        assertTrue(DrmLicenseGuard.storeLosesLicences());
    }

    @Test
    public void aFailureThatIsNotAMissingLicenceLeavesItToThePlayer() {
        assertArrayEquals(LICENCE, DrmLicenseGuard.restorable(LICENCE, id -> {
            throw new IllegalStateException("busy");
        }));
        assertFalse(DrmLicenseGuard.storeLosesLicences());
    }

    @Test
    public void eachLicenceIsCheckedOnce() {
        AtomicInteger tries = new AtomicInteger();
        DrmLicenseGuard.Probe dead = id -> {
            tries.incrementAndGet();
            throw new IllegalArgumentException("BAD_VALUE");
        };
        assertNull(DrmLicenseGuard.restorable(LICENCE, dead));
        assertNull(DrmLicenseGuard.restorable(LICENCE, dead));
        assertEquals(1, tries.get());
        assertNull(DrmLicenseGuard.restorable(OTHER, dead));
        assertEquals(2, tries.get());
    }

    @Test
    public void noLicenceStaysNoLicence() {
        assertNull(DrmLicenseGuard.restorable(null));
    }
}
