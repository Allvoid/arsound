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
    public void aClockMovedBackDoesNotOpenAnotherFifty() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        give(state, 50, NOW);
        // The clock went back two days: the moments are in the future, and count until 24 hours after themselves.
        long back = NOW - 48 * H;
        assertEquals(0, state.allowance(back));
        assertEquals(0, state.allowance(back + 40 * H));
        assertEquals(50, state.allowance(NOW + 24 * H + 1));
    }

    @Test
    public void backThenForwardAndForwardThenBackFindTheLikesWhereTheyWere() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        give(state, 50, NOW);
        // Forward 30 hours (the window is empty), then the clock comes back: the likes are counted again, nothing was dropped.
        assertEquals(50, state.allowance(NOW + 30 * H));
        assertEquals(0, state.allowance(NOW + H));
        // A like given while the clock was forward, then back: it counts too, until 24 hours after its own moment.
        state.count(NOW + 30 * H);
        assertEquals(1, state.countIn(NOW + H) - 50);
        assertEquals(49, state.allowance(NOW + 25 * H));
    }

    @Test
    public void theViewsOfTheWindowChangeNothing() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        String before = state.serialize();
        state.allowance(NOW + 30 * H);
        state.nextSlotAt(NOW + 30 * H);
        state.countIn(NOW - 10 * H);
        state.waitingForSlot(NOW + 40 * H);
        assertEquals(before, state.serialize());
    }

    @Test
    public void aChangeOfTimeZoneChangesNothing() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        // The moments are UTC: whichever zone the phone is in, the same likes are in the window.
        assertEquals(0, LikeQueueState.parse(state.serialize(), TimeZone.getTimeZone("Pacific/Auckland"), NOW + H).allowance(NOW + H));
        assertEquals(0, LikeQueueState.parse(state.serialize(), TimeZone.getTimeZone("America/Los_Angeles"), NOW + H).allowance(NOW + H));
    }

    @Test
    public void noFailureGivesTheCountBackBecauseNothingSaysWhatTheFailureWas() {
        // SoundCloud writes the like locally first and sends it later: no failure of the call tells a refused track.
        assertFalse(LikeQueueState.givesCountBack(new java.util.concurrent.TimeoutException("no answer")));
        assertFalse(LikeQueueState.givesCountBack(new java.io.IOException("reset")));
        assertFalse(LikeQueueState.givesCountBack(new IllegalStateException("No LikeToggler yet")));
        assertFalse(LikeQueueState.givesCountBack(new RuntimeException("404 not found")));
    }

    @Test
    public void aLikeIsGivenOnlyWhenItsCountIsWritten() {
        LikeQueueState state = new LikeQueueState();
        assertTrue(state.countAndKeep(NOW, () -> true));
        assertEquals(49, state.allowance(NOW));
        // Not written: taken back, and no like is to be given.
        assertFalse(state.countAndKeep(NOW + 1, () -> false));
        assertEquals(49, state.allowance(NOW + 1));
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
    public void theLikesOfACalendarDayAreMigratedAtTheMomentOfTheMigration() {
        String old = "arsound-like-queue 1\ntotal=60\ndone=30\nday=2026-11-18\ntoday=30\nsoundcloud:tracks:7\n";
        TimeZone zone = TimeZone.getTimeZone("Europe/Moscow");
        long dayStart = LikeQueueState.dayStart("2026-11-18", zone);
        // 50 given at 23:00 and read at 05:00 the next day are not 29 hours old: they are counted from the migration.
        long migrated = dayStart + 29 * H;
        LikeQueueState state = LikeQueueState.parse(old, zone, migrated);
        assertEquals(20, state.allowance(migrated + H));
        assertEquals(20, state.allowance(migrated + 23 * H));
        assertEquals(50, state.allowance(migrated + 24 * H + 1));
        // Saved anew it is the new kind: no day any more, the moments only.
        assertFalse(state.serialize().contains("day="));
        // A day long past has nothing in the window.
        assertEquals(50, LikeQueueState.parse(old, zone, dayStart + 10 * 24 * H).allowance(dayStart + 10 * 24 * H));
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
        // A day later it is still kept: the stamps are never dropped by age, a clock that jumped would find the file gone.
        assertTrue(state.worthKeeping(NOW + 25 * H));
    }

    @Test
    public void aLikeCountedBetweenTheCheckAndTheCountMakesTheQueueStopNotGiveThe51st() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 49, NOW);
        // The queue looked: one place left ...
        assertEquals(1, state.allowance(NOW));
        // ... and a heart by hand (or a My Wave like) was counted before the queue counted its own.
        state.count(NOW);
        assertEquals(LikeQueueState.Counted.LIMIT, state.countIfAllowed(NOW, () -> true));
        assertEquals(50, state.countIn(NOW));
    }

    @Test
    public void theLimitAndTheCountAreOneStep() {
        LikeQueueState state = new LikeQueueState();
        assertEquals(LikeQueueState.Counted.COUNTED, state.countIfAllowed(NOW, () -> true));
        assertEquals(LikeQueueState.Counted.NOT_WRITTEN, state.countIfAllowed(NOW, () -> false));
        assertEquals(1, state.countIn(NOW));
    }

    @Test
    public void aFileThatIsNotAQueueIsNotReadAsAnEmptyOne() {
        long now = NOW;
        TimeZone zone = TimeZone.getTimeZone("UTC");
        // A file cut to nothing by a power loss, another file, a number that is none.
        assertNull(LikeQueueState.tryParse("", zone, now));
        assertNull(LikeQueueState.tryParse("something else\n", zone, now));
        assertNull(LikeQueueState.tryParse("arsound-like-queue 1\ntotal=abc\n", zone, now));
        // No file at all is a fresh queue.
        assertTrue(LikeQueueState.tryParse(null, zone, now).isEmpty());
    }

    @Test
    public void afterAnUnreadableFileTheWindowCountsAsFullForADay() {
        LikeQueueState state = LikeQueueState.afterUnreadable(NOW);
        assertEquals(0, state.allowance(NOW + H));
        assertEquals(0, state.allowance(NOW + 24 * H - 1));
        assertEquals(50, state.allowance(NOW + 24 * H + 1));
    }

    @Test
    public void aClockFarBehindStillCountsTheLikesItGives() {
        LikeQueueState state = new LikeQueueState();
        // The real time's likes (a few) are kept; the clock then falls back to the year 2000.
        state.count(NOW);
        state.count(NOW + 1);
        state.count(NOW + 2);
        long dead = 946_684_800_000L;
        for (int i = 0; i < 100; i++) {
            if (state.countIfAllowed(dead + i, () -> true) == LikeQueueState.Counted.LIMIT) break;
        }
        // The new likes were not pruned as they came: the window filled, and fifty is the end.
        assertEquals(50, state.countIn(dead + 100));
    }

    @Test
    public void aClockForwardMoreThanADayThenASaveThenBackKeepsTheFifty() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        state.clear();
        // The clock jumped forward 30 hours: the window looks empty, but the file is not deleted for it.
        assertTrue(state.worthKeeping(NOW + 30 * H));
        LikeQueueState again = LikeQueueState.parse(state.serialize());
        // Back in real time: the fifty are all there.
        assertEquals(0, again.allowance(NOW + H));
    }

    @Test
    public void aClockForwardMoreThanAWeekThenALikeThenBackKeepsTheFifty() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        long forward = NOW + 10 * 24 * H;
        state.count(forward);
        // Nothing was dropped by age.
        assertEquals(51, state.countIn(NOW + H));
        assertEquals(0, state.allowance(NOW + H));
    }

    @Test
    public void likesGivenWhileTheClockWasADayBehindStillCountAfterItIsFixed() {
        LikeQueueState state = new LikeQueueState();
        state.count(NOW);
        // The clock fell back two days: the likes of that time are not stamped older than the newest one.
        long behind = NOW - 48 * H;
        for (int i = 0; i < 49; i++) state.count(behind + i);
        // The clock is set right an hour later: all fifty still count.
        assertEquals(0, state.allowance(NOW + H));
        assertTrue(state.clockLooksWrong(behind + 100));
        assertFalse(state.clockLooksWrong(NOW + H));
    }

    @Test
    public void onlyTheOldestBeyondTwoHundredStampsAreDropped() {
        LikeQueueState state = new LikeQueueState();
        for (int i = 0; i < 250; i++) state.count(NOW + i);
        LikeQueueState back = LikeQueueState.parse(state.serialize());
        assertEquals(LikeQueueState.KEEP_STAMPS, back.countIn(NOW));
    }

    @Test
    public void aFileTornInTheMiddleOfTheStampsIsNotAShorterQueue() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        give(state, 50, NOW);
        String whole = state.serialize();
        assertTrue(LikeQueueState.tryParse(whole, TimeZone.getTimeZone("UTC"), NOW) != null);
        // Cut inside the given= line: no end line.
        int cut = whole.indexOf("given=") + 40;
        assertNull(LikeQueueState.tryParse(whole.substring(0, cut), TimeZone.getTimeZone("UTC"), NOW));
        // Cut before the end line.
        assertNull(LikeQueueState.tryParse(whole.substring(0, whole.lastIndexOf("end")), TimeZone.getTimeZone("UTC"), NOW));
    }

    private static Moment at(long wall, long elapsed, String boot) {
        return new Moment(wall, elapsed, boot);
    }

    private static void giveAt(LikeQueueState state, int count, Moment moment) {
        for (int i = 0; i < count; i++) {
            state.count(new Moment(moment.wall, moment.elapsed + i, moment.boot));
            state.liked(state.peek());
        }
    }

    @Test
    public void onTheSameStartTheElapsedTimeIsTheTrueTimeWhateverTheWallClockSays() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        giveAt(state, 50, at(NOW, 1_000, "7"));
        // The wall clock jumps forward 25 hours (the network set it right): only an hour of the phone's own time passed.
        assertEquals(0, state.allowance(at(NOW + 25 * H, 1_000 + H, "7")));
        assertEquals(0, state.allowance(at(NOW + 25 * H, 1_000 + 24 * H - 1_000, "7")));
        // 24 hours of elapsed time later the window opens, whatever the wall clock says.
        assertEquals(50, state.allowance(at(NOW + H, 1_000 + 24 * H + 100, "7")));
    }

    @Test
    public void onAnotherStartTheWallClockIsAllThereIs() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        giveAt(state, 50, at(NOW, 1_000, "7"));
        assertEquals(50, state.allowance(at(NOW + 25 * H, 5, "8")));
        assertEquals(0, state.allowance(at(NOW + 23 * H, 5, "8")));
        // A stamp of an older file has no start: it is another start.
        LikeQueueState old = new LikeQueueState();
        old.count(NOW);
        assertEquals(49, LikeQueueState.parse(old.serialize()).allowance(Moment.wall(NOW + H)));
    }

    @Test
    public void aStrayFutureStampDoesNotKillTheQueueForAYearOnTheSameStart() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        long year = 365 * 24 * H;
        // One like while the clock was a year ahead, then the clock is set right.
        state.count(at(NOW + year, 1_000, "7"));
        giveAt(state, 49, at(NOW, 2_000, "7"));
        assertEquals(0, state.allowance(at(NOW, 3_000, "7")));
        // After 24 hours of the phone's own time all fifty are old, though the stamps' wall moments are a year ahead.
        assertEquals(50, state.allowance(at(NOW + 25 * H, 2_000 + 24 * H + 100, "7")));
    }

    @Test
    public void afterARebootAStrayFutureStampIsRepairedByTheUserNotForgotten() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        long year = 365 * 24 * H;
        state.count(at(NOW + year, 1_000, "7"));
        giveAt(state, 49, at(NOW, 2_000, "7"));
        Moment rebooted = at(NOW + 3 * H, 10, "8");
        // On the new start only the wall clock is left: the stamps are a year ahead, the window is full and the clock looks wrong.
        assertEquals(0, state.allowance(rebooted));
        assertTrue(state.repairOffered(rebooted));
        assertEquals(50, state.repairFuture(rebooted));
        // Never fewer counted: still full, but from now, so it opens 24 hours later.
        assertEquals(0, state.allowance(rebooted));
        assertFalse(state.repairOffered(rebooted));
        assertEquals(rebooted.wall + 24 * H, state.nextSlotAt(rebooted));
        assertEquals(50, state.allowance(at(rebooted.wall + 24 * H + 1, 10 + 24 * H + 1, "8")));
    }

    @Test
    public void theRepairIsNotOfferedWhileTheWindowIsNotFullOrTheClockIsRight() {
        LikeQueueState state = new LikeQueueState();
        state.count(at(NOW + 365 * 24 * H, 1, "7"));
        assertFalse(state.repairOffered(at(NOW, 2, "8")));
        LikeQueueState full = new LikeQueueState();
        full.add(urns(60), Collections.emptySet());
        giveAt(full, 50, at(NOW, 1, "7"));
        assertFalse(full.repairOffered(at(NOW + H, 5, "8")));
    }

    @Test
    public void theStampsKeepTheirStartAndElapsedTimeThroughTheFile() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        giveAt(state, 50, at(NOW, 1_000, "7"));
        LikeQueueState back = LikeQueueState.parse(state.serialize());
        assertEquals(0, back.allowance(at(NOW + 25 * H, 1_000 + H, "7")));
    }

    @Test
    public void anUnreadableQueueFileKeepsItsWaitingTracks() {
        String torn = "arsound-like-queue 1\ntotal=3\ndone=0\ngiven=1,2,3\nsoundcloud:tracks:11\nsoundcloud:tracks:12\nsoundcloud:tra";
        Moment now = at(NOW, 5, "7");
        LikeQueueState state = LikeQueueState.salvage(torn, now);
        // The intact lines are the queue; the cut one is not taken; the window is full for the day.
        assertEquals(2, state.remaining());
        assertEquals("soundcloud:tracks:11", state.peek());
        assertEquals(0, state.allowance(now));
        assertEquals(50, state.allowance(at(NOW + 24 * H + 1, 5 + 24 * H + 1, "7")));
    }

    @Test
    public void aClockSetRightRaisesWhatWasRecordedUnderAWrongOneForTheNextStart() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        // Start A: 10 likes at T. Start B: the clock is three days behind, 40 likes are stamped then.
        giveAt(state, 10, at(NOW, 1_000, "A"));
        giveAt(state, 40, at(NOW - 72 * H, 100, "B"));
        // The clock is set right on start B (an hour after T): the state is saved, which lifts the stamps of B.
        state.lift(at(NOW + H, 100 + 5_000, "B"));
        // Start C: only the wall clock is left. B's forty are not three days old: all fifty count.
        assertEquals(0, state.allowance(at(NOW + 2 * H, 10, "C")));
    }

    @Test
    public void theRepairIsNotForTheSameStartWhereTheElapsedTimeIsExact() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        giveAt(state, 49, at(NOW + 48 * H, 1_000, "7"));
        state.count(at(NOW + 48 * H, 2_000, "7"));
        // The clock is merely corrected back on the same start: full window, stamps ahead, but nothing to repair.
        assertFalse(state.repairOffered(at(NOW, 3_000, "7")));
        assertEquals(0, state.repairFuture(at(NOW, 3_000, "7")));
        // After a restart of the phone it is a case for repair.
        assertTrue(state.repairOffered(at(NOW, 10, "8")));
    }

    @Test
    public void aSalvagedStateIsAFileThatReadsAgain() {
        String torn = "arsound-like-queue 1\ntotal=2\ngiven=1\nsoundcloud:tracks:5\nsoundcloud:tracks:6\nsoundcloud:t";
        LikeQueueState saved = LikeQueueState.salvage(torn, at(NOW, 5, "7"));
        // Written at once, it is a whole file: the next start reads it, and the day's wait is not begun anew.
        LikeQueueState again = LikeQueueState.tryParse(saved.serialize(), TimeZone.getTimeZone("UTC"), NOW + 10 * H);
        assertTrue(again != null);
        assertEquals(2, again.remaining());
        assertEquals(0, again.allowance(at(NOW + 10 * H, 5 + 10 * H, "7")));
        assertEquals(50, again.allowance(at(NOW + 25 * H, 5 + 24 * H + 1, "7")));
    }

    @Test
    public void theSameGarbageIsNotCopiedAsideTwice() {
        assertTrue(LikeQueueState.needsCopy(null, "garbage"));
        assertFalse(LikeQueueState.needsCopy("garbage", "garbage"));
        assertTrue(LikeQueueState.needsCopy("garbage", "other garbage"));
    }

    @Test
    public void aClockFixedWhileTheWindowIsFullIsLiftedAndSavedByTheLooksWithoutAnyLike() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(120), Collections.emptySet());
        // Start B, the clock three days behind: fifty likes counted through the public step.
        Moment behind = at(NOW - 72 * H, 100, "B");
        for (int i = 0; i < 50; i++) {
            assertEquals(LikeQueueState.Counted.COUNTED,
                    state.countIfAllowed(new Moment(behind.wall, behind.elapsed + i, "B"), () -> true));
        }
        // The window is full, so nothing more is counted and nothing is saved; the clock is then set right (NTP).
        int[] saves = {0};
        // A look (the start of a run, the tick, the timer, the clock-change receiver) lifts and has it written.
        assertTrue(state.refreshAndKeep(at(NOW + H, 100 + 5_000, "B"), () -> {
            saves[0]++;
            return true;
        }));
        assertEquals(1, saves[0]);
        // Another look changes nothing and writes nothing.
        assertFalse(state.refreshAndKeep(at(NOW + H, 100 + 5_000, "B"), () -> {
            saves[0]++;
            return true;
        }));
        assertEquals(1, saves[0]);
        // The phone starts again: the fifty are not three days old by the wall clock.
        assertEquals(0, state.allowance(at(NOW + 2 * H, 10, "C")));
    }

    @Test
    public void aFailedWriteTakesTheCountBackEvenWhenTheWriteLiftedTheStamp() {
        LikeQueueState state = new LikeQueueState();
        Moment behind = at(NOW - 72 * H, 100, "B");
        // The write lifts the stamp (a clock set right) and then fails: the count must not stay in memory.
        boolean kept = state.countAndKeep(behind, () -> {
            state.lift(at(NOW, 200, "B"));
            return false;
        });
        assertFalse(kept);
        assertEquals(0, state.countIn(at(NOW, 300, "B")));
    }

    @Test
    public void withoutABootCountAForwardJumpOpensNothingWhileTheElapsedTimeGoesOn() {
        LikeQueueState state = new LikeQueueState();
        state.add(urns(60), Collections.emptySet());
        giveAt(state, 50, at(NOW, 1_000, ""));
        // The wall clock jumps 25 hours; the phone's own time went on by an hour only.
        assertEquals(0, state.allowance(at(NOW + 25 * H, 1_000 + H, "")));
        assertEquals(50, state.allowance(at(NOW + H, 1_000 + 24 * H + 100, "")));
        // The elapsed time went back: the phone started again, and only the wall clock is left.
        assertEquals(50, state.allowance(at(NOW + 25 * H, 5, "")));
        assertEquals(0, state.allowance(at(NOW + 23 * H, 5, "")));
        // The stamps keep their elapsed time through the file.
        LikeQueueState back = LikeQueueState.parse(state.serialize());
        assertEquals(0, back.allowance(at(NOW + 25 * H, 1_000 + H, "")));
    }
}
