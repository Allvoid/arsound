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
}
