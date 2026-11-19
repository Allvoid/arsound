package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class YearReviewTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("Europe/Moscow");
    private static final long MINUTE = 60_000;

    private static long at(int year, int month, int day, int hour) {
        Calendar calendar = Calendar.getInstance(ZONE);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, 0, 0);
        return calendar.getTimeInMillis();
    }

    private static ListenRecord listen(long start, String urn, long minutes) {
        return new ListenRecord(start, urn, minutes * MINUTE);
    }

    private static String artistOf(String urn) {
        if (urn.endsWith(":1") || urn.endsWith(":2")) return "Alpha Band";
        if (urn.endsWith(":3")) return "alpha band";
        if (urn.endsWith(":4")) return "Beta Duo";
        return "";
    }

    private static YearReview.Result year2026(List<ListenRecord> listens) {
        long[] bounds = YearReview.yearBounds(2026, ZONE);
        return YearReview.build(listens, bounds[0], bounds[1], YearReviewTest::artistOf, ZONE);
    }

    @Test
    public void totalsCountTimeAndPlaysOfThePeriodOnly() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2025, 12, 31, 20), "t:1", 10),
                listen(at(2026, 3, 1, 12), "t:1", 4),
                listen(at(2026, 3, 1, 13), "t:2", 0),
                listen(at(2027, 1, 1, 1), "t:1", 9)));
        YearReview.Result result = year2026(listens);
        assertEquals(4 * MINUTE, result.totalMs);
        assertEquals(1, result.plays);
        assertEquals(2, result.tracks);
    }

    @Test
    public void topTracksGoByPlaysThenTime() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2026, 5, 1, 10), "t:1", 3),
                listen(at(2026, 5, 2, 10), "t:2", 5),
                listen(at(2026, 5, 3, 10), "t:2", 5),
                listen(at(2026, 5, 4, 10), "t:4", 20)));
        YearReview.Result result = year2026(listens);
        assertEquals("t:2", result.topTracks.get(0).urn);
        assertEquals(2, result.topTracks.get(0).plays);
        assertEquals("t:4", result.topTracks.get(1).urn);
        assertEquals("t:1", result.topTracks.get(2).urn);
    }

    @Test
    public void spellingsOfOneArtistAreJoinedAndNamedByTheLongestListened() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2026, 5, 1, 10), "t:1", 10),
                listen(at(2026, 5, 2, 10), "t:3", 20),
                listen(at(2026, 5, 3, 10), "t:4", 15)));
        YearReview.Result result = year2026(listens);
        assertEquals(2, result.artists);
        assertEquals("alpha band", result.topArtists.get(0).name);
        assertEquals(30 * MINUTE, result.topArtists.get(0).playedMs);
        assertEquals("Beta Duo", result.topArtists.get(1).name);
    }

    @Test
    public void artistHeardBeforeThePeriodIsNotNew() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2025, 6, 1, 10), "t:1", 5),
                listen(at(2026, 5, 1, 10), "t:2", 5),
                listen(at(2026, 5, 2, 10), "t:4", 7)));
        YearReview.Result result = year2026(listens);
        assertEquals(1, result.newArtists);
        assertEquals(Arrays.asList("Beta Duo"), result.newArtistNames);
    }

    @Test
    public void tracksWithoutArtistCountInTimeButNotInArtists() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(listen(at(2026, 5, 1, 10), "t:9", 8)));
        YearReview.Result result = year2026(listens);
        assertEquals(8 * MINUTE, result.totalMs);
        assertEquals(0, result.artists);
        assertEquals(1, result.unknownArtistPlays);
        assertTrue(result.topArtists.isEmpty());
    }

    @Test
    public void periodsAreGregorianWhateverTheDefaultCalendar() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(listen(at(2026, 5, 1, 10), "t:1", 8)));
        long[] expected = YearReview.yearBounds(2026, ZONE);
        Locale saved = Locale.getDefault();
        // In the Thai Buddhist calendar 2026 is another year: the review must still count the Gregorian one.
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"));
        try {
            assertArrayEquals(expected, YearReview.yearBounds(2026, ZONE));
            YearReview.Result result = year2026(listens);
            assertEquals(Arrays.asList(2026), result.years);
            assertEquals(8 * MINUTE, result.totalMs);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    public void busiestDayAndLongestStreak() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2026, 2, 27, 10), "t:1", 5),
                listen(at(2026, 2, 28, 10), "t:1", 5),
                listen(at(2026, 3, 1, 10), "t:1", 6),
                listen(at(2026, 3, 1, 22), "t:2", 6),
                listen(at(2026, 3, 5, 10), "t:2", 2)));
        YearReview.Result result = year2026(listens);
        // Three days in a row over the end of February (2026 is not a leap year).
        assertEquals(3, result.longestStreak);
        assertEquals(2026, result.busiestYear);
        assertEquals(3, result.busiestMonth);
        assertEquals(1, result.busiestDay);
        assertEquals(12 * MINUTE, result.busiestMs);
    }

    @Test
    public void yearsAreListedNewestFirst() {
        List<ListenRecord> listens = new ArrayList<>(Arrays.asList(
                listen(at(2024, 1, 1, 10), "t:1", 1),
                listen(at(2026, 1, 1, 10), "t:1", 1),
                listen(at(2025, 1, 1, 10), "t:1", 1)));
        assertEquals(Arrays.asList(2026, 2025, 2024), year2026(listens).years);
    }

    @Test
    public void emptyPeriodIsEmpty() {
        assertTrue(year2026(new ArrayList<ListenRecord>()).isEmpty());
    }

    @Test
    public void dayNumbersFollowEachOther() {
        assertEquals(0, YearReview.dayNumber(1970, 1, 1));
        assertEquals(YearReview.dayNumber(2024, 2, 29) + 1, YearReview.dayNumber(2024, 3, 1));
        assertEquals(YearReview.dayNumber(2026, 12, 31) + 1, YearReview.dayNumber(2027, 1, 1));
    }
}
