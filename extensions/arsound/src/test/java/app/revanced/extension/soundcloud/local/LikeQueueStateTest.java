package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

public class LikeQueueStateTest {
    private static List<String> urns(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add("soundcloud:tracks:" + i);
        return list;
    }

    @Test
    public void queuesFromTheLastTrackToTheFirst() {
        LikeQueueState state = new LikeQueueState();
        assertEquals(3, state.add(urns(3), Collections.emptySet()));
        assertEquals("soundcloud:tracks:3", state.peek());
        state.liked("soundcloud:tracks:3");
        assertEquals("soundcloud:tracks:2", state.peek());
        state.liked("soundcloud:tracks:2");
        assertEquals("soundcloud:tracks:1", state.peek());
    }

    @Test
    public void leavesOutLikedAndQueuedTracks() {
        LikeQueueState state = new LikeQueueState();
        assertEquals(2, state.add(urns(3), new HashSet<>(Arrays.asList("soundcloud:tracks:2"))));
        // The same playlist again queues nothing new.
        assertEquals(0, state.add(urns(3), new HashSet<>(Arrays.asList("soundcloud:tracks:2"))));
        assertEquals(2, state.remaining());
        assertEquals(2, state.total());
    }

    private static final long H = 60L * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    private static void give(LikeQueueState state, int count, long at) {
        for (int i = 0; i < count; i++) {
            state.count(at);
            state.liked(state.peek());
        }
    }

    @Test
    public void givesFiftyInAnyTwentyFourHoursAndThenWaits() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        assertEquals(50, state.allowance(NOW));
        give(state, 50, NOW);
        assertEquals(0, state.allowance(NOW));
        assertTrue(state.waitingForSlot(NOW));
        assertEquals(70, state.remaining());
        assertEquals(50, state.done());
        // Not at midnight, not after 23 hours 59 minutes: only when the first of the fifty is 24 hours old.
        assertEquals(0, state.allowance(NOW + 24 * H - 1));
        assertEquals(NOW + 24 * H, state.nextSlotAt(NOW));
        assertEquals(50, state.allowance(NOW + 24 * H + 1));
    }

    @Test
    public void aSpreadOutDayFreesTheLikesOneByOne() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        give(state, 25, NOW);
        give(state, 25, NOW + 10 * H);
        assertEquals(0, state.allowance(NOW + 11 * H));
        assertEquals(NOW + 24 * H, state.nextSlotAt(NOW + 11 * H));
        // The first 25 are 24 hours old: 25 may go, not 50.
        assertEquals(25, state.allowance(NOW + 24 * H + 1));
    }

    @Test
    public void aClockMovedBackDoesNotOpenAnotherFiftyNorBlockForDays() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        give(state, 50, NOW);
        // The clock went back two days: the moments in the future count as now, so the likes are still counted ...
        long back = NOW - 48 * H;
        assertEquals(0, state.allowance(back));
        // ... and leave the window 24 hours after the moment they were seen, not 24 hours after the old future.
        assertEquals(0, state.allowance(back + 23 * H));
        assertEquals(50, state.allowance(back + 24 * H + 1));
    }

    @Test
    public void aChangeOfTimeZoneChangesNothing() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        // The moments are UTC: whichever zone the phone is in, the same likes are in the window.
        LikeQueueState back = LikeQueueState.parse(state.serialize(), TimeZone.getTimeZone("Pacific/Auckland"));
        assertEquals(0, back.allowance(NOW + H));
        assertEquals(0, LikeQueueState.parse(state.serialize(), TimeZone.getTimeZone("America/Los_Angeles")).allowance(NOW + H));
    }

    @Test
    public void aLikeWhoseResultIsNotKnownStaysCounted() {
        assertTrue(LikeQueueState.countsAsGiven(new java.util.concurrent.TimeoutException("no answer")));
        assertTrue(LikeQueueState.countsAsGiven(new java.io.IOException("reset")));
        assertTrue(LikeQueueState.countsAsGiven(new IllegalStateException("No LikeToggler yet")));
        // Only the track's own refusal gives the like back.
        assertFalse(LikeQueueState.countsAsGiven(new RuntimeException("404 not found")));
        LikeQueueState state = new LikeQueueState();
        state.count(NOW);
        state.giveBack(NOW);
        assertEquals(50, state.allowance(NOW));
    }

    @Test
    public void aLikeByHandCountsInTheWindowToo() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 49, NOW);
        state.count(NOW + H);
        assertEquals(0, state.allowance(NOW + 2 * H));
    }

    @Test
    public void theLikesOfACalendarDayAreMigratedOnce() {
        String old = "arsound-like-queue 1\ntotal=60\ndone=30\nday=2026-11-18\ntoday=30\nsoundcloud:tracks:7\n";
        TimeZone zone = TimeZone.getTimeZone("Europe/Moscow");
        LikeQueueState state = LikeQueueState.parse(old, zone);
        long dayStart = LikeQueueState.dayStart("2026-11-18", zone);
        assertEquals(20, state.allowance(dayStart + H));
        // Saved anew it is the new kind: no day any more, the moments only.
        assertFalse(state.serialize().contains("day="));
        assertEquals(20, LikeQueueState.parse(state.serialize(), zone).allowance(dayStart + H));
        assertEquals(50, state.allowance(dayStart + 25 * H));
    }

    @Test
    public void aQueueIsGivenLikesOnlyWhileItsAccountIsStillTheOne() {
        assertTrue(LikeQueueState.ownerStillSame("a-1", "a-1"));
        assertFalse(LikeQueueState.ownerStillSame("a-1", "b-2"));
        assertFalse(LikeQueueState.ownerStillSame("a-1", null));
        assertFalse(LikeQueueState.ownerStillSame(null, null));
    }

    @Test
    public void skippedTracksTakeNothingFromTheDay() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        state.skipped("soundcloud:tracks:3");
        assertEquals(50, state.allowance(NOW));
        assertEquals(1, state.done());
        assertEquals(2, state.remaining());
    }

    @Test
    public void postponedTrackGoesToTheBack() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        state.postponed("soundcloud:tracks:3");
        assertEquals("soundcloud:tracks:2", state.peek());
        assertEquals(3, state.remaining());
    }

    @Test
    public void survivesTheTrip() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        for (int i = 60; i > 53; i--) state.liked("soundcloud:tracks:" + i);
        LikeQueueState back = LikeQueueState.parse(state.serialize());
        assertEquals(state.peek(), back.peek());
        assertEquals(53, back.remaining());
        assertEquals(60, back.total());
        assertEquals(7, back.done());
    }

    @Test
    public void damagedTextIsAnEmptyQueue() {
        assertTrue(LikeQueueState.parse("garbage").isEmpty());
        assertTrue(LikeQueueState.parse(null).isEmpty());
        assertTrue(LikeQueueState.parse("arsound-like-queue 1\ntotal=abc\n").isEmpty());
        assertNull(LikeQueueState.parse("").peek());
    }

    @Test
    public void aNewQueueAfterAnEmptyOneCountsAnew() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(2), Collections.emptySet());
        state.liked("soundcloud:tracks:2");
        state.liked("soundcloud:tracks:1");
        state.add(urns(5), Collections.emptySet());
        assertEquals(5, state.total());
        assertEquals(0, state.done());
    }

    @Test
    public void aLikeOfAStoppedQueueIsCountedButDoesNotLeaveTheNewOne() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        // The queue was stopped and filled again while the like of track 3 was on its way.
        state.clear();
        state.add(urns(2), Collections.emptySet());
        state.liked("soundcloud:tracks:3");
        assertEquals("soundcloud:tracks:2", state.peek());
        assertEquals(2, state.remaining());
        assertEquals(0, state.done());
    }

    @Test
    public void aTrackNotAtTheFrontIsLeftAlone() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        state.skipped("soundcloud:tracks:1");
        state.postponed("soundcloud:tracks:1");
        assertEquals("soundcloud:tracks:3", state.peek());
        assertEquals(3, state.remaining());
        assertEquals(0, state.done());
    }

    @Test
    public void aCopyDoesNotFollowLaterChanges() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        LikeQueueState copy = state.copy();
        state.liked("soundcloud:tracks:3");
        assertEquals(3, copy.remaining());
        assertEquals(0, copy.done());
        assertEquals(2, state.remaining());
        assertEquals(1, state.done());
    }

    @Test
    public void accountsGetTheirOwnFileKeys() {
        assertEquals("matvey_40_mail_2e_ru-123", LikeQueueState.keyOf("matvey@mail.ru-123"));
        assertEquals("abc-9", LikeQueueState.keyOf("abc-9"));
        assertFalse(LikeQueueState.keyOf("a.b").equals(LikeQueueState.keyOf("a_b")));
    }

    @Test
    public void onlyAFailureOfTheTrackItselfMayDropIt() {
        // No like service yet, no answer in time, no network: the moment's fault, the track stays queued.
        assertFalse(LikeQueueState.isTrackFailure(new IllegalStateException("No LikeToggler yet")));
        assertFalse(LikeQueueState.isTrackFailure(new java.util.concurrent.TimeoutException("Completable timed out")));
        assertFalse(LikeQueueState.isTrackFailure(new java.net.UnknownHostException("api-v2.soundcloud.com")));
        assertTrue(LikeQueueState.isTrackFailure(new RuntimeException("404 not found")));
        // Wrapped by SoundCloud: still the network's.
        assertFalse(LikeQueueState.isTrackFailure(new RuntimeException("like failed", new java.io.IOException("reset"))));
        assertFalse(LikeQueueState.isTrackFailure(new RuntimeException("a", new RuntimeException("b", new java.util.concurrent.TimeoutException()))));
    }

    @Test
    public void aStoppedQueueKeepsTheCountOfTheWindow() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        state.clear();
        // Nothing waits, but 50 were given lately: the file stays, or a new queue would give 50 more.
        assertTrue(state.worthKeeping(NOW + H));
        LikeQueueState again = LikeQueueState.parse(state.serialize());
        again.add(urns(10), Collections.emptySet());
        assertEquals(0, again.allowance(NOW + H));
        // A day later, nothing waiting: nothing to keep.
        assertFalse(state.worthKeeping(NOW + 25 * H));
    }
}
