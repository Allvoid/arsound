package app.revanced.extension.soundcloud.theme.effect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyframesTest {
    @Test
    public void aLoopThatHasNotStartedGivesMinusOne() {
        assertEquals(-1f, Keyframes.progress(1f, 5f, 2f), 0f);
    }

    @Test
    public void aNegativeDelayMeansTheLoopIsAlreadyRunning() {
        assertEquals(.5f, Keyframes.progress(1f, 4f, -1f), 1e-6f);
    }

    @Test
    public void theLoopStartsAgainAfterItsDuration() {
        assertEquals(.25f, Keyframes.progress(9f, 4f, 0f), 1e-6f);
    }

    @Test
    public void easeGoesFromZeroToOneThroughTheMiddle() {
        assertEquals(0f, Keyframes.ease(0f), 1e-6f);
        assertEquals(.5f, Keyframes.ease(.5f), 1e-6f);
        assertEquals(1f, Keyframes.ease(1f), 1e-6f);
    }

    @Test
    public void pulseGoesUpToOneAtTheMiddleAndBack() {
        assertEquals(0f, Keyframes.pulse(0f), 1e-6f);
        assertEquals(1f, Keyframes.pulse(.5f), 1e-6f);
        assertEquals(0f, Keyframes.pulse(1f), 1e-6f);
    }

    @Test
    public void theDesignsRandomSequenceStartsFromSeedSeven() {
        ParticleRandom random = new ParticleRandom();
        assertEquals((7L * 16807 % 2147483647 - 1) / 2147483646f, random.next(), 0f);
    }

    @Test
    public void everyEffectDrawsTheSameSequence() {
        ParticleRandom first = new ParticleRandom();
        ParticleRandom second = new ParticleRandom();
        for (int i = 0; i < 100; i++) {
            float value = first.next();
            assertEquals(value, second.next(), 0f);
            assertTrue(value >= 0f && value <= 1f);
        }
    }

    @Test
    public void everyThemeIdOfAnEffectIsFound() {
        for (EffectKind kind : EffectKind.values()) assertEquals(kind, EffectKind.fromId(kind.id));
        assertNotNull(EffectKind.fromId("winter"));
    }

    @Test
    public void aThemeWithoutAnEffectHasNone() {
        assertNull(EffectKind.fromId("soundcloud"));
        assertNull(EffectKind.fromId(null));
    }

    @Test
    public void strikesStayRareAndInOrder() {
        for (long number = 0; number < 200; number++) {
            float gap = StormScene.strikeTime(number + 1) - StormScene.strikeTime(number);
            org.junit.Assert.assertTrue("gap " + gap, gap >= 7);
            org.junit.Assert.assertEquals(number, StormScene.lastStrike(StormScene.strikeTime(number) + .01f));
        }
        org.junit.Assert.assertEquals(0, StormScene.flash(10), 0);
    }

    @Test
    public void theRecordRunsDownToAStopAndComesBackUpToSpeed() {
        float speed = VinylScene.nextSpeed(0, true, 100);
        org.junit.Assert.assertEquals(100, speed, 0);
        org.junit.Assert.assertEquals(0, VinylScene.nextSpeed(speed, false, 100), 0);
        org.junit.Assert.assertEquals(50, VinylScene.nextSpeed(100, false, .75f), .01f);
    }
}
