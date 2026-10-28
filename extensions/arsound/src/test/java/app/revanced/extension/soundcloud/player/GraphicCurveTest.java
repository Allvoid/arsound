package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GraphicCurveTest {
    private static final float[] FLAT = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final float[] SLOPE = {6, 5, 4, 3, 2, 1, 0, -1, -2, -3};

    @Test
    public void flatCurveIsFlatOnAnyBands() {
        assertArrayEquals(new short[]{0, 0, 0, 0, 0},
                GraphicCurve.onBands(FLAT, new int[]{60, 230, 910, 3600, 14000}, (short) -1500, (short) 1500));
    }

    @Test
    public void bandOnAClassicFrequencyTakesThatBandsLevel() {
        // 6 dB is 600 mB; 31 Hz is the first classic band, 16 kHz the last.
        short[] levels = GraphicCurve.onBands(SLOPE, new int[]{31, 1000, 16000}, (short) -1500, (short) 1500);
        assertEquals(600, levels[0]);
        assertEquals(100, levels[1]);
        assertEquals(-300, levels[2]);
    }

    @Test
    public void frequenciesOutsideTheClassicRangeUseTheEnds() {
        short[] levels = GraphicCurve.onBands(SLOPE, new int[]{10, 20000}, (short) -1500, (short) 1500);
        assertEquals(600, levels[0]);
        assertEquals(-300, levels[1]);
    }

    @Test
    public void levelsStayWithinThePhonesRange() {
        float[] loud = {12, 12, 12, 12, 12, 12, 12, 12, 12, 12};
        short[] levels = GraphicCurve.onBands(loud, new int[]{100, 1000}, (short) -1000, (short) 1000);
        assertEquals(1000, levels[0]);
        assertEquals(1000, levels[1]);
    }

    @Test
    public void levelsMoveInHalfDecibelSteps() {
        float[] curve = {0, 0, 0, 0, 0, 3, 3, 3, 3, 3};
        short[] levels = GraphicCurve.onBands(curve, new int[]{700}, (short) -1500, (short) 1500);
        assertEquals(0, levels[0] % 50);
        assertTrue(levels[0] > 0 && levels[0] < 300);
    }

    @Test
    public void choiceTextHasTheFormsTheSettingsRead() {
        assertEquals("u:My mix", EqualizerChoice.userPreset("My mix"));
        assertTrue(EqualizerChoice.isUserPreset("u:My mix", "My mix"));
        assertFalse(EqualizerChoice.isUserPreset("p:bass", "bass"));
        assertFalse(EqualizerChoice.isUserPreset("", "x"));
        assertEquals("p:", EqualizerChoice.BUILT_IN_PREFIX);
    }
}
