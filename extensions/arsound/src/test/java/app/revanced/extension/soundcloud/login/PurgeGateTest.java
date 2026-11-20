package app.revanced.extension.soundcloud.login;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PurgeGateTest {
    @Test
    public void aRunningWipeIsBusyUntilItEnds() {
        PurgeGate gate = new PurgeGate();
        assertFalse(gate.busy(1_000));
        gate.begin(1_000);
        assertTrue(gate.busy(2_000));
        gate.end();
        assertFalse(gate.busy(2_000));
    }

    @Test
    public void twoWipesAreBothWaitedFor() {
        PurgeGate gate = new PurgeGate();
        gate.begin(1_000);
        gate.begin(2_000);
        gate.end();
        assertTrue(gate.busy(3_000));
        gate.end();
        gate.end();
        assertFalse(gate.busy(3_000));
    }

    @Test
    public void aWipeWhoseEndNeverComesDoesNotBlockForEver() {
        PurgeGate gate = new PurgeGate();
        gate.begin(1_000);
        assertTrue(gate.busy(1_000 + PurgeGate.LOST_AFTER_MS - 1));
        assertFalse(gate.busy(1_000 + PurgeGate.LOST_AFTER_MS));
    }

    @Test
    public void theGateAndTheMarkOfASwitchAgreeOnHowLongAWipeIsWaitedFor() {
        org.junit.Assert.assertEquals(AccountVault.PENDING_HERE_FOR_MS, PurgeGate.LOST_AFTER_MS);
    }
}
