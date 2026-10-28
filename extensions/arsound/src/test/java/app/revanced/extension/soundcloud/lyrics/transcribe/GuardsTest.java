package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class GuardsTest {
    private static final long GB = 1024L * 1024 * 1024;

    private static Guards.Device device(int percent, boolean charging, boolean saver, long memory) {
        return new Guards.Device(percent, charging, saver, memory);
    }

    @Test
    public void aHealthyPhoneHasNoConcerns() {
        assertTrue(Guards.concerns(device(80, false, false, 4 * GB), AsrModel.SMALL).isEmpty());
    }

    @Test
    public void aLowBatteryOnlyMattersWhenNotCharging() {
        List<Guards.Concern> concerns = Guards.concerns(device(12, false, false, 4 * GB), AsrModel.SMALL);
        assertEquals(1, concerns.size());
        assertEquals(Guards.Concern.LOW_BATTERY, concerns.get(0));
        assertFalse(concerns.get(0).blocking);
        assertTrue(Guards.concerns(device(12, true, false, 4 * GB), AsrModel.SMALL).isEmpty());
    }

    @Test
    public void anUnknownBatteryRaisesNoConcern() {
        assertTrue(Guards.concerns(device(-1, false, false, 4 * GB), AsrModel.SMALL).isEmpty());
    }

    @Test
    public void powerSavingIsOnlyAWarning() {
        List<Guards.Concern> concerns = Guards.concerns(device(90, false, true, 4 * GB), AsrModel.SMALL);
        assertEquals(Guards.Concern.POWER_SAVER, concerns.get(0));
        assertFalse(concerns.get(0).blocking);
    }

    @Test
    public void tooLittleMemoryBlocksAndTheLighterModelMayFit() {
        Guards.Device tight = device(90, true, false, 1 * GB);
        List<Guards.Concern> concerns = Guards.concerns(tight, AsrModel.SMALL);
        assertEquals(Guards.Concern.NOT_ENOUGH_MEMORY, concerns.get(0));
        assertTrue(concerns.get(0).blocking);
        assertTrue(Guards.lighterModelFits(tight, AsrModel.SMALL));
        assertTrue(Guards.concerns(tight, AsrModel.BASE).isEmpty());
        assertFalse(Guards.lighterModelFits(tight, AsrModel.BASE));
    }

    @Test
    public void veryLittleMemoryFitsNothing() {
        Guards.Device none = device(90, true, false, 300L * 1024 * 1024);
        assertFalse(Guards.lighterModelFits(none, AsrModel.SMALL));
        assertEquals(Guards.Concern.NOT_ENOUGH_MEMORY, Guards.concerns(none, AsrModel.BASE).get(0));
    }

    @Test
    public void theHotterThePhoneTheLongerTheRest() {
        assertEquals(0, Guards.thermalPauseMs(0));
        assertEquals(0, Guards.thermalPauseMs(1));
        assertTrue(Guards.thermalPauseMs(2) > 0);
        assertTrue(Guards.thermalPauseMs(3) > Guards.thermalPauseMs(2));
        assertEquals(Guards.thermalPauseMs(3), Guards.thermalPauseMs(5));
    }
}
