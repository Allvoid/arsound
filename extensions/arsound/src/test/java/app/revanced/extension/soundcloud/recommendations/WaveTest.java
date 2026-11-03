package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class WaveTest {
    private static WaveMixer.Offer offer(String urn, double score) {
        return new WaveMixer.Offer(urn, score);
    }

    // region ListenVerdict

    @Test
    public void aTrackSwitchedOffInTheFirstSecondsIsSkipped() {
        assertEquals(ListenVerdict.SKIPPED, ListenVerdict.of(0, 200_000));
        assertEquals(ListenVerdict.SKIPPED, ListenVerdict.of(14_999, 200_000));
        assertEquals(ListenVerdict.BETWEEN, ListenVerdict.of(15_000, 200_000));
    }

    @Test
    public void aTrackHeardForAGoodWhileIsHeard() {
        assertEquals(ListenVerdict.BETWEEN, ListenVerdict.of(44_999, 200_000));
        assertEquals(ListenVerdict.HEARD, ListenVerdict.of(45_000, 200_000));
        assertEquals(ListenVerdict.HEARD, ListenVerdict.of(190_000, 200_000));
    }

    @Test
    public void aShortTrackHeardThroughIsHeardEvenIfShorterThanTheLimits() {
        assertEquals(ListenVerdict.HEARD, ListenVerdict.of(10_000, 10_500));
        assertEquals(ListenVerdict.HEARD, ListenVerdict.of(30_000, 40_000));
        assertEquals(ListenVerdict.BETWEEN, ListenVerdict.of(20_000, 100_000));
    }

    @Test
    public void aLengthThePlayerCannotMeanIsNotKnown() {
        assertTrue(ListenVerdict.isKnownLength(200_000));
        assertTrue(ListenVerdict.isKnownLength(ListenVerdict.MAX_KNOWN_MS));
        assertFalse(ListenVerdict.isKnownLength(0));
        assertFalse(ListenVerdict.isKnownLength(-1));
        // ExoPlayer's "time unset".
        assertFalse(ListenVerdict.isKnownLength(Long.MIN_VALUE + 1));
        assertFalse(ListenVerdict.isKnownLength(ListenVerdict.MAX_KNOWN_MS + 1));
        assertFalse(ListenVerdict.isKnownLength(Long.MAX_VALUE));
    }

    @Test
    public void anUnknownOrAbsurdLengthIsJudgedByTheTimePlayedAlone() {
        for (long length : new long[]{0, -1, Long.MIN_VALUE + 1, Long.MAX_VALUE}) {
            assertEquals(ListenVerdict.SKIPPED, ListenVerdict.of(5_000, length));
            assertEquals(ListenVerdict.BETWEEN, ListenVerdict.of(20_000, length));
            assertEquals(ListenVerdict.HEARD, ListenVerdict.of(60_000, length));
        }
    }

    // endregion

    // region WaveLedger

    @Test
    public void aSkipAndAHeardReplaceEachOther() {
        WaveLedger ledger = new WaveLedger();
        ledger.record("soundcloud:tracks:1", ListenVerdict.HEARD, 1000);
        ledger.record("soundcloud:tracks:1", ListenVerdict.SKIPPED, 2000);
        assertTrue(ledger.isSkipped("soundcloud:tracks:1"));
        assertTrue(ledger.heardNewestFirst().isEmpty());
        ledger.record("soundcloud:tracks:1", ListenVerdict.HEARD, 3000);
        assertFalse(ledger.isSkipped("soundcloud:tracks:1"));
        assertEquals(Collections.singletonList("soundcloud:tracks:1"), ledger.heardNewestFirst());
    }

    @Test
    public void aListenInBetweenChangesNothing() {
        WaveLedger ledger = new WaveLedger();
        ledger.record("soundcloud:tracks:1", ListenVerdict.BETWEEN, 1000);
        assertTrue(ledger.skipped().isEmpty());
        assertTrue(ledger.heardNewestFirst().isEmpty());
    }

    @Test
    public void theLedgerSurvivesBeingWrittenAndRead() {
        WaveLedger ledger = new WaveLedger();
        ledger.record("soundcloud:tracks:1", ListenVerdict.SKIPPED, 1000);
        ledger.record("soundcloud:tracks:2", ListenVerdict.HEARD, 2000);
        ledger.record("soundcloud:tracks:3", ListenVerdict.HEARD, 3000);
        WaveLedger read = WaveLedger.parse(ledger.format());
        assertEquals(Collections.singletonList("soundcloud:tracks:1"), read.skipped());
        assertEquals(Arrays.asList("soundcloud:tracks:3", "soundcloud:tracks:2"), read.heardNewestFirst());
    }

    @Test
    public void aDamagedLedgerLosesOnlyTheDamagedItems() {
        WaveLedger read = WaveLedger.parse("ssoundcloud:tracks:1=abc hsoundcloud:tracks:2=5 x s= hsoundcloud:tracks:3");
        assertTrue(read.skipped().isEmpty());
        assertEquals(Collections.singletonList("soundcloud:tracks:2"), read.heardNewestFirst());
        assertTrue(WaveLedger.parse(null).skipped().isEmpty());
    }

    @Test
    public void oldAndTooManyItemsAreDropped() {
        WaveLedger ledger = new WaveLedger();
        ledger.record("old", ListenVerdict.SKIPPED, 0);
        long now = WaveLedger.SKIP_KEPT_MS + 10;
        ledger.record("new", ListenVerdict.SKIPPED, now);
        assertEquals(Collections.singletonList("new"), ledger.skipped());
        for (int i = 0; i < WaveLedger.HEARD_LIMIT + 20; i++) ledger.record("h" + i, ListenVerdict.HEARD, now + i);
        assertEquals(WaveLedger.HEARD_LIMIT, ledger.heardNewestFirst().size());
        assertEquals("h" + (WaveLedger.HEARD_LIMIT + 19), ledger.heardNewestFirst().get(0));
    }

    // endregion

    // region WaveLearner

    private static final String TRACK_A = "soundcloud:tracks:1";
    private static final String TRACK_B = "soundcloud:tracks:2";

    @Test
    public void aShortTrackPlayedThroughIsHeardOnlyWhenItsLengthIsKnown() {
        WaveLearner knows = new WaveLearner();
        knows.noteLength(TRACK_A, 12_000);
        assertTrue(knows.listenEnded(TRACK_A, 11_500, 1));
        WaveLedger ledger = new WaveLedger();
        assertTrue(knows.applyTo(ledger));
        assertEquals(Collections.singletonList(TRACK_A), ledger.heardNewestFirst());
        assertTrue(ledger.skipped().isEmpty());

        // The same listen without the length (the hook used to pass 0) was taken for a skip.
        WaveLearner blind = new WaveLearner();
        assertTrue(blind.listenEnded(TRACK_A, 11_500, 1));
        WaveLedger wrong = new WaveLedger();
        blind.applyTo(wrong);
        assertEquals(Collections.singletonList(TRACK_A), wrong.skipped());
    }

    @Test
    public void aShortTrackSwitchedOffEarlyIsStillSkippedWithItsLengthKnown() {
        WaveLearner learner = new WaveLearner();
        learner.noteLength(TRACK_A, 40_000);
        assertTrue(learner.listenEnded(TRACK_A, 4_000, 1));
        WaveLedger ledger = new WaveLedger();
        learner.applyTo(ledger);
        assertEquals(Collections.singletonList(TRACK_A), ledger.skipped());
    }

    @Test
    public void aShortTrackHeardForMostOfItIsHeardNotLeftInBetween() {
        WaveLearner learner = new WaveLearner();
        learner.noteLength(TRACK_A, 40_000);
        assertTrue(learner.listenEnded(TRACK_A, 30_000, 1));
        learner.noteLength(TRACK_B, 100_000);
        // 30 s of 100 s says nothing, as before.
        assertFalse(learner.listenEnded(TRACK_B, 30_000, 2));
        WaveLedger ledger = new WaveLedger();
        learner.applyTo(ledger);
        assertEquals(Collections.singletonList(TRACK_A), ledger.heardNewestFirst());
    }

    @Test
    public void theLastNotedLengthOfATrackCounts() {
        WaveLearner learner = new WaveLearner();
        learner.noteLength(TRACK_A, 180_000);
        assertEquals(180_000, learner.lengthOf(TRACK_A));
        // The report of the player itself: the track is a 30 s preview.
        learner.noteLength(TRACK_A, 30_000);
        assertEquals(30_000, learner.lengthOf(TRACK_A));
        assertEquals(0, learner.lengthOf(TRACK_B));
        assertEquals(0, learner.lengthOf(null));
    }

    @Test
    public void aLengthThatIsNotKnownDoesNotReplaceOneThatIs() {
        WaveLearner learner = new WaveLearner();
        learner.noteLength(TRACK_A, 90_000);
        learner.noteLength(TRACK_A, 0);
        learner.noteLength(TRACK_A, -5);
        learner.noteLength(TRACK_A, Long.MIN_VALUE + 1);
        learner.noteLength(TRACK_A, Long.MAX_VALUE);
        learner.noteLength(null, 90_000);
        assertEquals(90_000, learner.lengthOf(TRACK_A));
    }

    @Test
    public void aSoundsUrnAndATracksUrnAreTheSameTrack() {
        WaveLearner learner = new WaveLearner();
        learner.noteLength("soundcloud:sounds:7", 20_000);
        assertEquals(20_000, learner.lengthOf("soundcloud:tracks:7"));
    }

    @Test
    public void onlyTheLastLengthsAreKept() {
        WaveLearner learner = new WaveLearner();
        int last = WaveLearner.LENGTHS_KEPT + 2;
        for (int i = 0; i <= last; i++) learner.noteLength("soundcloud:tracks:" + i, 60_000 + i);
        assertEquals(0, learner.lengthOf("soundcloud:tracks:0"));
        assertEquals(0, learner.lengthOf("soundcloud:tracks:2"));
        assertEquals(60_003, learner.lengthOf("soundcloud:tracks:3"));
        assertEquals(60_000 + last, learner.lengthOf("soundcloud:tracks:" + last));
        // A report of the track that plays now makes it one of the newest again.
        learner.noteLength("soundcloud:tracks:3", 60_003);
        learner.noteLength("soundcloud:tracks:100", 60_000);
        assertEquals(60_003, learner.lengthOf("soundcloud:tracks:3"));
        assertEquals(0, learner.lengthOf("soundcloud:tracks:4"));
    }

    @Test
    public void listensWaitInTheOrderTheyEndedUntilTheLedgerTakesThem() {
        WaveLearner learner = new WaveLearner();
        // Skipped, then heard to the end: the later one wins, as when they are recorded one by one.
        assertTrue(learner.listenEnded(TRACK_A, 2_000, 1_000));
        assertTrue(learner.listenEnded(TRACK_A, 80_000, 2_000));
        assertTrue(learner.listenEnded(TRACK_B, 3_000, 3_000));
        WaveLedger ledger = new WaveLedger();
        assertTrue(learner.applyTo(ledger));
        assertEquals(Collections.singletonList(TRACK_A), ledger.heardNewestFirst());
        assertEquals(Collections.singletonList(TRACK_B), ledger.skipped());
        // Nothing is applied twice.
        assertFalse(learner.applyTo(new WaveLedger()));
    }

    @Test
    public void aListenKeepsTheTimeItEndedNotTheTimeTheLedgerTookIt() {
        WaveLearner learner = new WaveLearner();
        long ended = 5_000;
        learner.listenEnded(TRACK_A, 2_000, ended);
        WaveLedger ledger = new WaveLedger();
        learner.applyTo(ledger);
        // Older than the time a skip is kept: the next record forgets it, so the time of the listen was used.
        ledger.record(TRACK_B, ListenVerdict.SKIPPED, ended + WaveLedger.SKIP_KEPT_MS + 1);
        assertEquals(Collections.singletonList(TRACK_B), ledger.skipped());
    }

    @Test
    public void listensThatSayNothingOrAreNotSoundCloudTracksAreNotKept() {
        WaveLearner learner = new WaveLearner();
        assertFalse(learner.listenEnded(TRACK_A, 20_000, 1));
        assertFalse(learner.listenEnded("soundcloud:ads:5", 1_000, 1));
        assertFalse(learner.listenEnded("local:file", 1_000, 1));
        assertFalse(learner.listenEnded(null, 1_000, 1));
        assertFalse(learner.applyTo(new WaveLedger()));
    }

    @Test
    public void forgettingDropsTheListensThatWait() {
        WaveLearner learner = new WaveLearner();
        learner.listenEnded(TRACK_A, 2_000, 1);
        learner.dropWaiting();
        WaveLedger ledger = new WaveLedger();
        assertFalse(learner.applyTo(ledger));
        assertTrue(ledger.skipped().isEmpty());
    }

    // endregion

    // region WaveMixer

    @Test
    public void aTrackSeveralSourcesPointToComesFirst() {
        WaveMixer mixer = new WaveMixer(Collections.emptyList(), Collections.emptyList(), new Random(1));
        List<String> mixed = mixer.mix(Arrays.asList(offer("a", 1), offer("b", 1), offer("b", 1), offer("b", 1), offer("c", 1)), 10);
        assertEquals("b", mixed.get(0));
        assertEquals(3, mixed.size());
    }

    @Test
    public void skippedDislikedAndAlreadyQueuedTracksNeverCome() {
        WaveMixer mixer = new WaveMixer(Arrays.asList("skipped", "disliked", "queued"), Collections.emptyList(), new Random(2));
        List<String> mixed = mixer.mix(Arrays.asList(offer("skipped", 9), offer("disliked", 9), offer("queued", 9), offer("fine", 1)), 10);
        assertEquals(Collections.singletonList("fine"), mixed);
    }

    @Test
    public void aLikedTrackComesRarely() {
        int came = 0;
        // One generator for all the tries: the first numbers of generators with close seeds are close too.
        WaveMixer mixer = new WaveMixer(Collections.emptyList(), Collections.singletonList("liked"), new Random(12345));
        for (int attempt = 0; attempt < 400; attempt++) {
            if (mixer.mix(Collections.singletonList(offer("liked", 1)), 5).contains("liked")) came++;
        }
        assertTrue("came " + came + " times of 400", came > 50 && came < 150);
    }

    @Test
    public void theMixHasAtMostTheLimit() {
        List<WaveMixer.Offer> offers = new ArrayList<>();
        for (int i = 0; i < 40; i++) offers.add(offer("t" + i, 1));
        assertEquals(12, new WaveMixer(Collections.emptyList(), Collections.emptyList(), new Random(3)).mix(offers, 12).size());
    }

    @Test
    public void equalScoresComeInAShuffledOrder() {
        List<WaveMixer.Offer> offers = new ArrayList<>();
        for (int i = 0; i < 10; i++) offers.add(offer("t" + i, 1));
        List<String> first = new WaveMixer(Collections.emptyList(), Collections.emptyList(), new Random(4)).mix(offers, 10);
        List<String> second = new WaveMixer(Collections.emptyList(), Collections.emptyList(), new Random(5)).mix(offers, 10);
        assertFalse(first.equals(second));
    }

    @Test
    public void seedsAreChosenByWeightWithoutRepeating() {
        Map<String, Integer> pool = new LinkedHashMap<>();
        pool.put("playing", 4);
        pool.put("liked", 3);
        pool.put("heard", 2);
        List<String> seeds = WaveMixer.pickSeeds(pool, 5, new Random(6));
        assertEquals(3, seeds.size());
        assertEquals(3, new java.util.HashSet<>(seeds).size());
        assertTrue(WaveMixer.pickSeeds(Collections.emptyMap(), 3, new Random(7)).isEmpty());
    }

    // endregion

    @Test
    public void theListShowsTwoPlayedTracksAndTheNextOnes() {
        assertEquals(0, MyWave.windowStart(-1, 30, 2, 10));
        assertEquals(0, MyWave.windowStart(1, 30, 2, 10));
        assertEquals(5, MyWave.windowStart(7, 30, 2, 10));
        // Near the end the window slides back, so it stays full.
        assertEquals(20, MyWave.windowStart(28, 30, 2, 10));
        assertEquals(0, MyWave.windowStart(3, 6, 2, 10));
    }

    @Test
    public void aSkippedTrackRestsAFewDaysAndThenMayComeAgain() {
        WaveLedger ledger = new WaveLedger();
        long skippedAt = 1_000_000L;
        ledger.record("soundcloud:tracks:1", ListenVerdict.SKIPPED, skippedAt);
        assertTrue(ledger.isResting("soundcloud:tracks:1", skippedAt + WaveLedger.SKIP_REST_MS - 1));
        assertEquals(Collections.singletonList("soundcloud:tracks:1"), ledger.resting(skippedAt + 1));
        assertFalse(ledger.isResting("soundcloud:tracks:1", skippedAt + WaveLedger.SKIP_REST_MS));
        assertTrue(ledger.resting(skippedAt + WaveLedger.SKIP_REST_MS).isEmpty());
        // Still not a seed: it was skipped, only no longer kept away.
        assertTrue(ledger.isSkipped("soundcloud:tracks:1"));
    }
}
