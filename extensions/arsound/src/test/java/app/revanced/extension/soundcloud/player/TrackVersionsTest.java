package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackVersionsTest {
    @Test
    public void normalSpeedIsTheTracksOwn() {
        assertEquals(1f, VersionSpeed.NORMAL.factor(85, 125), 0f);
    }

    @Test
    public void slowedIsSlowerAndSpeedUpIsFaster() {
        assertEquals(0.85f, VersionSpeed.SLOWED.factor(85, 125), 1e-6f);
        assertEquals(1.25f, VersionSpeed.SPEED_UP.factor(85, 125), 1e-6f);
    }

    @Test
    public void aWrongPercentIsKeptOnItsSideOfNormal() {
        assertEquals(0.99f, VersionSpeed.SLOWED.factor(120, 125), 1e-6f);
        assertEquals(0.5f, VersionSpeed.SLOWED.factor(10, 125), 1e-6f);
        assertEquals(1.01f, VersionSpeed.SPEED_UP.factor(85, 50), 1e-6f);
        assertEquals(2f, VersionSpeed.SPEED_UP.factor(85, 500), 1e-6f);
    }

    @Test
    public void speedsAreFoundByTheirKey() {
        assertEquals(VersionSpeed.SLOWED, VersionSpeed.of("slowed"));
        assertEquals(VersionSpeed.SPEED_UP, VersionSpeed.of("speedup"));
        assertEquals(VersionSpeed.NORMAL, VersionSpeed.of("off"));
        assertEquals(VersionSpeed.NORMAL, VersionSpeed.of("nonsense"));
        assertEquals(VersionSpeed.NORMAL, VersionSpeed.of(null));
    }

    @Test
    public void strongerMeansMoreBassAndALongerTail() {
        assertFalse(VersionStrength.OFF.isOn());
        assertTrue(VersionStrength.LOW.isOn());
        assertTrue(VersionStrength.LOW.bassPermille < VersionStrength.MID.bassPermille);
        assertTrue(VersionStrength.MID.bassPermille < VersionStrength.HIGH.bassPermille);
        assertTrue(VersionStrength.LOW.reverbDecayMillis < VersionStrength.HIGH.reverbDecayMillis);
        assertTrue(VersionStrength.LOW.reverbLevelMillibels < VersionStrength.HIGH.reverbLevelMillibels);
        assertEquals(0, VersionStrength.OFF.bassPermille);
    }

    @Test
    public void theBassShelfIsFourSevenTenDecibels() {
        assertEquals(0f, VersionStrength.OFF.bassShelfDb(), 0f);
        assertEquals(4f, VersionStrength.LOW.bassShelfDb(), 0f);
        assertEquals(7f, VersionStrength.MID.bassShelfDb(), 0f);
        assertEquals(10f, VersionStrength.HIGH.bassShelfDb(), 0f);
    }

    @Test
    public void strengthsAreFoundByTheirKey() {
        assertEquals(VersionStrength.MID, VersionStrength.of("mid"));
        assertEquals(VersionStrength.OFF, VersionStrength.of(""));
        assertEquals(VersionStrength.OFF, VersionStrength.of("loud"));
    }
}
