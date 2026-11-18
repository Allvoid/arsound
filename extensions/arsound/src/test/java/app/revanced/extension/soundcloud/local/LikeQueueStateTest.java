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
        state.liked("d1");
        assertEquals("soundcloud:tracks:2", state.peek());
        state.liked("d1");
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
        for (int i = 0; i < 50; i++) state.liked("d1");
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
        state.skipped();
        assertEquals(50, state.allowance("d1"));
        assertEquals(1, state.done());
        assertEquals(2, state.remaining());
    }

    @Test
    public void postponedTrackGoesToTheBack() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(3), Collections.emptySet());
        state.postponed();
        assertEquals("soundcloud:tracks:2", state.peek());
        assertEquals(3, state.remaining());
    }

    @Test
    public void survivesTheTrip() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        for (int i = 0; i < 7; i++) state.liked("2026-11-18");
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
        state.liked("d1");
        state.liked("d1");
        state.add(urns(5), Collections.emptySet());
        assertEquals(5, state.total());
        assertEquals(0, state.done());
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
}
