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
        state.liked("soundcloud:tracks:3", "d1");
        assertEquals("soundcloud:tracks:2", state.peek());
        state.liked("soundcloud:tracks:2", "d1");
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

    @Test
    public void givesFiftyALikeAndWaitsForTheNextDay() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        assertEquals(50, state.allowance("d1"));
        // The head of the queue is the last track of the playlist: 120 first, then 119 and so on.
        for (int i = 120; i > 70; i--) state.liked("soundcloud:tracks:" + i, "d1");
        assertEquals(0, state.allowance("d1"));
        assertTrue(state.dayUsed("d1"));
        assertEquals(70, state.remaining());
        assertEquals(50, state.done());
        // The next day the count starts again.
        assertFalse(state.dayUsed("d2"));
        assertEquals(50, state.allowance("d2"));
    }

    @Test
    public void skippedTracksTakeNothingFromTheDay() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        state.skipped("soundcloud:tracks:3");
        assertEquals(50, state.allowance("d1"));
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
        for (int i = 60; i > 53; i--) state.liked("soundcloud:tracks:" + i, "2026-11-18");
        LikeQueueState back = LikeQueueState.parse(state.serialize());
        assertEquals(state.peek(), back.peek());
        assertEquals(53, back.remaining());
        assertEquals(60, back.total());
        assertEquals(7, back.done());
        assertEquals(7, back.givenOn("2026-11-18"));
        assertEquals(0, back.givenOn("2026-11-19"));
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
        state.liked("soundcloud:tracks:2", "d1");
        state.liked("soundcloud:tracks:1", "d1");
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
        state.liked("soundcloud:tracks:3", "d1");
        assertEquals("soundcloud:tracks:2", state.peek());
        assertEquals(2, state.remaining());
        assertEquals(0, state.done());
        // The server took the like, so it counts for the day.
        assertEquals(49, state.allowanceOf("d1"));
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
        state.liked("soundcloud:tracks:3", "d1");
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
    public void dayKeysFollowTheZone() {
        TimeZone moscow = TimeZone.getTimeZone("Europe/Moscow");
        TimeZone la = TimeZone.getTimeZone("America/Los_Angeles");
        // 2026-11-17 22:30 UTC is already the 18th in Moscow and still the 17th in Los Angeles.
        long moment = 1_794_954_600_000L;
        assertEquals("2026-11-18", LikeQueueState.dayKey(moment, moscow));
        assertEquals("2026-11-17", LikeQueueState.dayKey(moment, la));
        assertEquals("2026-11-19", LikeQueueState.dayKey(LikeQueueState.nextDayStart(moment, moscow), moscow));
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
    public void aStoppedQueueKeepsTheCountOfTheDay() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        for (int i = 0; i < 50; i++) state.liked(state.peek(), "d1");
        state.clear();
        // Nothing waits, but 50 were given today: the file stays, or a new queue would give 50 more and risk a block.
        assertTrue(state.worthKeeping("d1"));
        LikeQueueState again = LikeQueueState.parse(state.serialize());
        again.add(urns(10), Collections.emptySet());
        assertEquals(0, again.allowance("d1"));
        // Another day, nothing waiting: nothing to keep.
        assertFalse(state.worthKeeping("d2"));
    }
}
