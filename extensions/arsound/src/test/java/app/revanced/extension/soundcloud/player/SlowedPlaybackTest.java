package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SlowedPlaybackTest {
    @Test
    public void titlesThatSayTheSpeedIsChangedAreLeftAlone() {
        assertTrue(SlowedPlayback.isRetimed("linkin park - numb (slowed + reverb)"));
        assertTrue(SlowedPlayback.isRetimed("Linkin Park - Numb slowed"));
        assertTrue(SlowedPlayback.isRetimed("track [Slowed]"));
        assertTrue(SlowedPlayback.isRetimed("song (sped up)"));
        assertTrue(SlowedPlayback.isRetimed("song speed-up version"));
        assertTrue(SlowedPlayback.isRetimed("Song (Nightcore)"));
        assertTrue(SlowedPlayback.isRetimed("song slow & reverb"));
        assertTrue(SlowedPlayback.isRetimed("Трек (slowed)"));
        assertTrue(SlowedPlayback.isRetimed("Трек замедленный"));
        assertTrue(SlowedPlayback.isRetimed("Трек ускоренная версия"));
    }

    @Test
    public void ordinaryTitlesAreSlowed() {
        assertFalse(SlowedPlayback.isRetimed("Numb"));
        assertFalse(SlowedPlayback.isRetimed("Slow Dancing in the Dark"));
        assertFalse(SlowedPlayback.isRetimed("Speed of Sound"));
        assertFalse(SlowedPlayback.isRetimed("Slowdive - Alison"));
        assertFalse(SlowedPlayback.isRetimed("Вечность"));
        assertFalse(SlowedPlayback.isRetimed(null));
        assertFalse(SlowedPlayback.isRetimed(""));
    }
}
