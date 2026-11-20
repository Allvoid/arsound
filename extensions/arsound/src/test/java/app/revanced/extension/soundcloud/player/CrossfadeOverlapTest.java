package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Test;

public class CrossfadeOverlapTest {
    private static final long FADE = 6_000;
    private static final long FADE_STARTS = 1_000;

    @Test
    public void theSecondPlayerStartsALittleBeforeTheFade() {
        // The lead is 250 ms: the player is started early enough to be heard from the fade's start.
        assertEquals(FADE_STARTS - 250, CrossfadeOverlap.startAt(FADE_STARTS));
    }

    @Test
    public void theGaplessChangeStartsJustBeforeTheEndNotEarlier() {
        // No fade: the sound is not faded in, so the lead is only what a started player needs to make its first sound.
        assertEquals(FADE_STARTS - 80, CrossfadeOverlap.startAt(FADE_STARTS, 0));
        assertEquals(FADE_STARTS - 250, CrossfadeOverlap.startAt(FADE_STARTS, FADE));
    }

    @Test
    public void withoutAFadeTheSecondPlayerIsFullFromTheEndOfTheTrack() {
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS - 1, FADE_STARTS, 0), 0f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + 1, FADE_STARTS, 0), 0f);
    }

    @Test
    public void theFadeInIsSilentBeforeItStartsAndFullAtItsEnd() {
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS - 1, FADE_STARTS, FADE), 0f);
        assertEquals(0f, CrossfadeOverlap.fadeInLevel(FADE_STARTS, FADE_STARTS, FADE), 0f);
        assertEquals(0.25f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + FADE / 2, FADE_STARTS, FADE), 1e-6f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + FADE, FADE_STARTS, FADE), 0f);
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(FADE_STARTS + 2 * FADE, FADE_STARTS, FADE), 0f);
    }

    @Test
    public void aFadeWithoutLengthDoesNotDivideByZero() {
        assertEquals(1f, CrossfadeOverlap.fadeInLevel(5_000, 4_000, 0), 0f);
    }

    @Test
    public void theHandoffFadesOutUnderSoundCloud() {
        assertEquals(1f, CrossfadeOverlap.handoffLevel(500, 500), 0f);
        assertEquals(0.5f, CrossfadeOverlap.handoffLevel(580, 500), 1e-6f);
        assertEquals(0f, CrossfadeOverlap.handoffLevel(660, 500), 0f);
        assertEquals(0f, CrossfadeOverlap.handoffLevel(9_000, 500), 0f);
    }

    @Test
    public void theSecondPlayerPlaysAtTheSpeedSoundCloudWouldGiveTheTrack() {
        // The common choice slows a track, unless its title says it is retimed already.
        assertTrue(CrossfadeOverlap.playsSlowed(null, VersionSpeed.SLOWED, false));
        assertFalse(CrossfadeOverlap.playsSlowed(null, VersionSpeed.SLOWED, true));
        assertFalse(CrossfadeOverlap.playsSlowed(null, VersionSpeed.NORMAL, false));
        // The track's own speed holds whatever its title says and whatever is chosen for the rest.
        assertTrue(CrossfadeOverlap.playsSlowed(VersionSpeed.SPEED_UP, VersionSpeed.NORMAL, true));
        assertFalse(CrossfadeOverlap.playsSlowed(VersionSpeed.NORMAL, VersionSpeed.SLOWED, false));
    }

    @Test
    public void aSilenceWithAFadeIsAPause() {
        assertFalse(CrossfadeOverlap.isPaused(6_000, 11_000, 10_000, 15_000));
        assertTrue(CrossfadeOverlap.isPaused(6_000, 11_501, 10_000, 10_200));
    }

    @Test
    public void withoutAFadeOnlyASilenceFarFromTheEndIsAPause() {
        // The track played out: the last report was 300 ms before its end.
        assertFalse(CrossfadeOverlap.isPaused(0, 12_000, 10_000, 10_300));
        // Paused with 5 s left.
        assertTrue(CrossfadeOverlap.isPaused(0, 12_000, 10_000, 15_000));
        // Reports are still coming.
        assertFalse(CrossfadeOverlap.isPaused(0, 10_500, 10_000, 15_000));
    }

    @Test
    public void theSecondPlayerIsPreparedEarlierToOpenAStream() {
        // The fade, the 250 ms lead, a second of slack and six seconds for a stream to open.
        assertEquals(6_000 + 250 + 1_000 + 6_000, CrossfadeOverlap.startWindow(6_000));
        assertEquals(0 + 250 + 1_000 + 6_000, CrossfadeOverlap.startWindow(0));
    }

    @Test
    public void aStreamedNextTrackDoesNotOverlap() {
        // SoundCloud starts a streamed track from 0, so the second player's head start would sound twice.
        assertFalse(CrossfadeOverlap.canHandOver(null));
        assertFalse(CrossfadeOverlap.canHandOver(""));
        assertTrue(CrossfadeOverlap.canHandOver("/storage/emulated/0/Music/a.mp3"));
        assertTrue(CrossfadeOverlap.canHandOver("content://media/external/audio/media/5"));
    }

    private static Object get(String name) throws Exception {
        Field field = CrossfadeOverlap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = CrossfadeOverlap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object fakePlayer() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field the = unsafeClass.getDeclaredField("theUnsafe");
        the.setAccessible(true);
        Object unsafe = the.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, android.media.MediaPlayer.class);
    }

    @Test
    public void aStopAfterAPauseLetsTheTrackStartTheOverlapAgain() throws Exception {
        set("player", fakePlayer());
        set("starting", true);
        long before = (Long) get("attempt");
        // The overlap stopped because the track was paused: the start must not stay marked as pending, or no later
        // report would ever start the overlap for this track.
        assertTrue(CrossfadeOverlap.detach(true) != null);
        assertFalse((Boolean) get("starting"));
        assertTrue((Long) get("attempt") > before);
        assertTrue(get("player") == null);
    }

    @Test
    public void aStopAfterTheHandoffLeavesTheNextTracksStartAlone() throws Exception {
        set("player", fakePlayer());
        set("starting", true);
        long before = (Long) get("attempt");
        CrossfadeOverlap.detach(false);
        assertTrue((Boolean) get("starting"));
        assertEquals(before, (long) (Long) get("attempt"));
        set("starting", false);
    }

    @Test
    public void withTheEqualizerOnOrAPreampTheSecondPlayerIsNotUsed() {
        // It is outside the player's sound chain: it would come in at another level, without the curve.
        assertTrue(CrossfadeOverlap.outsideChain(true, 0));
        assertTrue(CrossfadeOverlap.outsideChain(false, -2000));
        assertTrue(CrossfadeOverlap.outsideChain(false, 500));
        assertFalse(CrossfadeOverlap.outsideChain(false, 0));
    }

    @Test
    public void theTimeLeftIsWallTimeNotMediaTime() {
        // At 140 % speed 10 s of the track pass in about 7.1 s.
        assertEquals(7_143, CrossfadeOverlap.wallLeft(10_000, 1.4f));
        assertEquals(11_765, CrossfadeOverlap.wallLeft(10_000, 0.85f));
        assertEquals(10_000, CrossfadeOverlap.wallLeft(10_000, 1f));
        assertEquals(10_000, CrossfadeOverlap.wallLeft(10_000, 0f));
    }

    @Test
    public void aSpeedChosenInTheMiddleOfATrackMovesTheEndAndTheFadeWithIt() {
        long now = 100_000;
        long left = 20_000;
        long fade = 6_000;
        // Normal speed: the end is 20 s away and the fade begins 14 s from now.
        long end = CrossfadeOverlap.endAtFor(now, left, 1f);
        assertEquals(now + 20_000, end);
        assertEquals(now + 14_000, CrossfadeOverlap.fadeStartFor(end, fade));
        // Slowed 85 % chosen: the same 20 s of the track take 23.5 s, so the fade begins 2.5 s later, not 2.5 s too early.
        long slowedEnd = CrossfadeOverlap.endAtFor(now, left, 0.85f);
        assertEquals(now + 23_529, slowedEnd);
        assertEquals(now + 17_529, CrossfadeOverlap.fadeStartFor(slowedEnd, fade));
        // Sped up to 140 %: it is over sooner.
        assertEquals(now + 14_286, CrossfadeOverlap.endAtFor(now, left, 1.4f));
    }

    @Test
    public void theSecondPlayerWaitsAgainWhenTheFadeMovedWhileItWaited() {
        // The start moved 100 ms later (a speed chosen meanwhile): it is posted again for that time.
        assertEquals(100, CrossfadeOverlap.rewait(1_000, 900));
        // Within 40 ms of the time, or past it: it starts.
        assertEquals(0, CrossfadeOverlap.rewait(1_000, 960));
        assertEquals(0, CrossfadeOverlap.rewait(1_000, 2_000));
    }

    @Test
    public void onlyTracksAndImportedFilesHaveASpeedOfTheirOwn() {
        assertTrue(CrossfadeOverlap.hasSpeed("soundcloud:tracks:42", false));
        // An imported file: no ":tracks:" in its urn, a file of the phone.
        assertTrue(CrossfadeOverlap.hasSpeed("arsound:local:abc", true));
        // An ad, or nothing: the normal speed.
        assertFalse(CrossfadeOverlap.hasSpeed("soundcloud:ads:7", false));
        assertFalse(CrossfadeOverlap.hasSpeed(null, true));
    }

    @Test
    public void aFileNamedSlowedIsLeftAtItsOwnSpeedLikeThePlayerDoes() {
        // The file name of an imported file says it is retimed: with Slowed chosen the player stays at 1.0, so does the overlap.
        assertTrue(SlowedPlayback.isRetimed("x (slowed).mp3"));
        assertFalse(CrossfadeOverlap.playsSlowed(null, VersionSpeed.SLOWED, SlowedPlayback.isRetimed("x (slowed).mp3")));
    }
}
