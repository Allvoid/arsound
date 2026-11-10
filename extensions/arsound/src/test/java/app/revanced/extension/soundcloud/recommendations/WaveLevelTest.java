package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WaveLevelTest {
    @Test
    public void pausedHeadsForZero() {
        assertEquals(0f, WaveLevel.target(false, 0.9f, 1f, 3.0), 0f);
    }

    @Test
    public void playingFollowsTheLoudnessOfTheMusic() {
        float quiet = WaveLevel.target(true, 0.05f, 1f, 0.0);
        float loud = WaveLevel.target(true, 0.9f, 1f, 0.0);
        assertTrue(loud > quiet + 0.5f);
    }

    @Test
    public void withoutLoudnessTheVolumeWithTheMockupsShiverIsUsed() {
        for (double t = 0; t < 30; t += 0.7) {
            float target = WaveLevel.target(true, -1f, 0.6f, t);
            assertTrue(target >= 0.6f * 0.44f - 1e-4 && target <= 0.6f * 1.0f + 1e-4);
        }
        assertEquals(0f, WaveLevel.target(true, -1f, 0f, 1.0), 0f);
    }

    @Test
    public void targetStaysInZeroToOne() {
        for (double t = 0; t < 20; t += 0.3) {
            float high = WaveLevel.target(true, 1f, 1f, t);
            assertTrue(high >= 0f && high <= 1f);
        }
    }

    @Test
    public void levelRisesFasterThanItFalls() {
        float up = WaveLevel.follow(0f, 1f, 0.1f);
        float down = 1f - WaveLevel.follow(1f, 0f, 0.1f);
        assertTrue(up > down);
        assertEquals(1f - (float) Math.exp(-0.35), up, 1e-5f);
        assertEquals(1f - (float) Math.exp(-0.2), down, 1e-5f);
    }

    @Test
    public void levelDoesNotMoveWithoutTime() {
        assertEquals(0.3f, WaveLevel.follow(0.3f, 0.9f, 0f), 0f);
    }

    @Test
    public void shapeNumbersMatchTheMockup() {
        assertEquals(6f, WaveLevel.amplitude(0f), 1e-6f);
        assertEquals(36f, WaveLevel.amplitude(1f), 1e-6f);
        assertEquals(0.4f, WaveLevel.speed(0f), 1e-6f);
        assertEquals(3.0f, WaveLevel.speed(1f), 1e-6f);
        assertEquals(1.8f, WaveLevel.frequency(1f), 1e-6f);
        assertEquals(0.8f, WaveLevel.weight(0f), 1e-6f);
        assertEquals(2.0f, WaveLevel.weight(1f), 1e-6f);
    }
}
