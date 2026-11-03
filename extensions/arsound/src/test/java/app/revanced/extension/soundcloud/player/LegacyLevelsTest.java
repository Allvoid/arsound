package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LegacyLevelsTest {
    @Test
    public void tenBandsStayAsTheyAre() {
        short[] levels = {0, 50, 100, 150, 200, 250, 300, 350, 400, 450};
        assertArrayEquals(levels, LegacyLevels.onTenBands(levels, 10));
    }

    @Test
    public void nothingSavedIsFlat() {
        assertArrayEquals(new short[10], LegacyLevels.onTenBands(new short[0], 10));
    }

    @Test
    public void fiveBandsGoOntoTenByTheirPlace() {
        short[] ten = LegacyLevels.onTenBands(new short[]{600, 0, 0, 0, -600}, 10);
        assertEquals(10, ten.length);
        // The lowest bands keep the lift of the lowest old band, the highest the cut of the highest.
        assertEquals(600, ten[0]);
        assertEquals(-600, ten[9]);
        assertTrue(ten[1] >= ten[5] && ten[5] >= ten[8]);
    }

    @Test
    public void aFlatCurveStaysFlat() {
        assertArrayEquals(new short[10], LegacyLevels.onTenBands(new short[]{0, 0, 0, 0, 0}, 10));
    }

    @Test
    public void theResultStaysInTheRangeOfTheBands() {
        short[] ten = LegacyLevels.onTenBands(new short[]{3000, -3000, 3000}, 10);
        for (short level : ten) assertTrue(level >= AudioEqualizer.BAND_MIN && level <= AudioEqualizer.BAND_MAX);
    }
}
