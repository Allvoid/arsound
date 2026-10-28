package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordScorerTest {
    private static LrclibRecord record(String track, String artist, double seconds, String plain, String synced) {
        return new LrclibRecord(1, track, artist, seconds, false, plain, synced);
    }

    @Test
    public void exactRecordScoresFull() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        RecordScorer.Score score = RecordScorer.score(record("Song", "Singer", 200, "words", "[00:01.00] words"), query);
        assertEquals(1.0, score.total, 1e-9);
        assertTrue(score.passes());
        assertTrue(score.isSynced());
    }

    @Test
    public void plainRecordLosesTheSyncedShare() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        RecordScorer.Score score = RecordScorer.score(record("Song", "Singer", 200, "words", ""), query);
        assertEquals(0.9, score.total, 1e-9);
        assertFalse(score.isSynced());
    }

    @Test
    public void recordWithoutTextScoresNothing() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        RecordScorer.Score score = RecordScorer.score(record("Song", "Singer", 200, "", ""), query);
        assertEquals(0.0, score.total, 1e-9);
        assertFalse(score.passes());
    }

    @Test
    public void anotherArtistNeverPasses() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        RecordScorer.Score score = RecordScorer.score(record("Song", "Somebody Else", 200, "words", "x"), query);
        assertFalse(score.artistFits);
        assertFalse(score.passes());
    }

    @Test
    public void lengthSteps() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        // 0.4 title + 0.25 artist + 0.25 length + 0.1 synced, the length share by the difference in seconds.
        assertEquals(0.4 + 0.25 + 0.25 * 0.75 + 0.1,
                RecordScorer.score(record("Song", "Singer", 204, "w", "s"), query).total, 1e-9);
        assertEquals(0.4 + 0.25 + 0.25 * 0.45 + 0.1,
                RecordScorer.score(record("Song", "Singer", 190, "w", "s"), query).total, 1e-9);
        assertEquals(0.4 + 0.25 + 0.1,
                RecordScorer.score(record("Song", "Singer", 100, "w", "s"), query).total, 1e-9);
    }

    @Test
    public void timingsFollowOnlyAClosedLength() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        assertTrue(RecordScorer.timingsFit(record("Song", "Singer", 205, "", "s"), query));
        assertFalse(RecordScorer.timingsFit(record("Song", "Singer", 207, "", "s"), query));
        assertFalse(RecordScorer.timingsFit(record("Song", "Singer", Double.NaN, "", "s"), query));
    }

    @Test
    public void versionTitlesAllowLessDrift() {
        TrackQuery query = TrackQuery.of("Singer - Song (Live)", "Singer", 200_000);
        assertTrue(RecordScorer.timingsFit(record("Song", "Singer", 202, "", "s"), query));
        assertFalse(RecordScorer.timingsFit(record("Song", "Singer", 204, "", "s"), query));
    }

    @Test
    public void stampsMustEndWhereTheTrackEnds() {
        TrackQuery query = TrackQuery.of("Singer - Song", "Singer", 200_000);
        Lyrics fits = new Lyrics(new String[]{"a", "b"}, new long[]{1_000, 204_000});
        Lyrics runsOn = new Lyrics(new String[]{"a", "b"}, new long[]{1_000, 206_000});
        assertTrue(RecordScorer.endsInTime(fits, query));
        assertFalse(RecordScorer.endsInTime(runsOn, query));
    }
}
