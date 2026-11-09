package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class ListenBucketsTest {
    private static final TimeZone MOSCOW = TimeZone.getTimeZone("Europe/Moscow");
    private static final long MINUTE = 60_000;

    private static long at(int year, int month, int day, int hour) {
        Calendar calendar = Calendar.getInstance(MOSCOW);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, 0, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void sevenDaysAreSevenDailyBarsEndingToday() {
        long now = at(2026, 11, 9, 15);
        ListenBuckets buckets = ListenBuckets.forPeriod(7, now, MOSCOW);
        long[] starts = buckets.starts();
        assertEquals(ListenBuckets.Unit.DAY, buckets.unit);
        assertEquals(7, starts.length);
        assertEquals(at(2026, 11, 3, 0), starts[0]);
        assertEquals(at(2026, 11, 9, 0), starts[6]);
    }

    @Test
    public void listensFallIntoTheirLocalDay() {
        long now = at(2026, 11, 9, 15);
        ListenBuckets buckets = ListenBuckets.forPeriod(7, now, MOSCOW);
        buckets.add(at(2026, 11, 9, 0) + MINUTE, 3 * MINUTE);
        buckets.add(at(2026, 11, 8, 23), 5 * MINUTE);
        buckets.add(at(2026, 11, 8, 10), 2 * MINUTE);
        // Before the first bar and in the future: left out.
        buckets.add(at(2026, 11, 1, 10), 9 * MINUTE);
        buckets.add(at(2026, 11, 10, 10), 9 * MINUTE);
        long[] values = buckets.values(buckets.starts());
        assertEquals(3 * MINUTE, values[6]);
        assertEquals(7 * MINUTE, values[5]);
        assertEquals(0, values[0]);
    }

    @Test
    public void aYearIsTwelveMonthsAcrossNewYear() {
        long now = at(2026, 2, 15, 12);
        ListenBuckets buckets = ListenBuckets.forPeriod(365, now, MOSCOW);
        long[] starts = buckets.starts();
        assertEquals(ListenBuckets.Unit.MONTH, buckets.unit);
        assertEquals(12, starts.length);
        assertEquals(at(2025, 3, 1, 0), starts[0]);
        assertEquals(at(2026, 2, 1, 0), starts[11]);
        buckets.add(at(2025, 12, 31, 23), MINUTE);
        buckets.add(at(2026, 1, 1, 1), 2 * MINUTE);
        long[] values = buckets.values(starts);
        assertEquals(MINUTE, values[9]);
        assertEquals(2 * MINUTE, values[10]);
    }

    @Test
    public void allTimeGoesBackToTheFirstYearWithAListen() {
        long now = at(2026, 11, 9, 15);
        ListenBuckets buckets = ListenBuckets.forPeriod(0, now, MOSCOW);
        assertEquals(ListenBuckets.MIN_YEARS, buckets.starts().length);
        buckets.add(at(2019, 6, 1, 12), 4 * MINUTE);
        buckets.add(at(2026, 1, 1, 12), MINUTE);
        long[] starts = buckets.starts();
        assertEquals(8, starts.length);
        assertEquals(at(2019, 1, 1, 0), starts[0]);
        long[] values = buckets.values(starts);
        assertArrayEquals(new long[]{4 * MINUTE, 0, 0, 0, 0, 0, 0, MINUTE}, values);
    }

    @Test
    public void nothingPlayedCountsNothing() {
        ListenBuckets buckets = ListenBuckets.forPeriod(30, at(2026, 11, 9, 15), MOSCOW);
        buckets.add(at(2026, 11, 9, 10), 0);
        long[] starts = buckets.starts();
        assertEquals(30, starts.length);
        for (long value : buckets.values(starts)) assertEquals(0, value);
    }

    @Test
    public void aDamagedRecordFrom1970DoesNotStretchTheYears() {
        ListenBuckets buckets = ListenBuckets.forPeriod(0, at(2026, 11, 9, 15), MOSCOW);
        buckets.add(5_000, 4 * MINUTE);
        assertEquals(ListenBuckets.MIN_YEARS, buckets.starts().length);
    }
}
