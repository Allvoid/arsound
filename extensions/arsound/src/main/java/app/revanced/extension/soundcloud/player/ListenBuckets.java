package app.revanced.extension.soundcloud.player;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * The time listened, by day, month or year, for the bar chart of the statistics: a period of up to a month is shown by
 * days, a year by months and all time by years, as on the desktop. Counted in the phone's time zone, in the same pass
 * over the history as the tops.
 */
public final class ListenBuckets {
    /** The length of one bar. */
    public enum Unit {
        DAY(Calendar.DAY_OF_MONTH),
        MONTH(Calendar.MONTH),
        YEAR(Calendar.YEAR);

        final int field;

        Unit(int field) {
            this.field = field;
        }
    }

    /** All time is shown by years, at least this many, so one year of history is not one lonely bar. */
    static final int MIN_YEARS = 3;

    public final Unit unit;
    private final TimeZone zone;
    /** The bar of now is the last one. */
    private final long current;
    /** The first bar of a period of fixed length; for all time the first year with a listen decides. */
    private final long first;
    private final Map<Long, Long> sums = new TreeMap<>();

    private ListenBuckets(Unit unit, int count, long now, TimeZone zone) {
        this.unit = unit;
        this.zone = zone;
        current = start(now);
        first = shift(current, -(count - 1));
    }

    /**
     * The bars for the period of the statistics.
     *
     * @param days 7, 30 or 365, or 0 for all time.
     */
    public static ListenBuckets forPeriod(long days, long now, TimeZone zone) {
        if (days == 0) return new ListenBuckets(Unit.YEAR, MIN_YEARS, now, zone);
        if (days > 31) return new ListenBuckets(Unit.MONTH, 12, now, zone);
        return new ListenBuckets(Unit.DAY, (int) Math.max(1, days), now, zone);
    }

    /** Counts a listen that started at {@code start} into its bar. Outside the bars (a clock set back) it is left. */
    public void add(long start, long playedMs) {
        if (playedMs <= 0) return;
        long bar = start(start);
        if (bar > current || (unit != Unit.YEAR && bar < first)) return;
        sums.merge(bar, playedMs, Long::sum);
    }

    /** Where each bar starts, oldest first; the last is the bar of now. */
    public long[] starts() {
        long from = first;
        if (unit == Unit.YEAR && !sums.isEmpty()) from = Math.min(from, sums.keySet().iterator().next());
        int count = 1;
        for (long at = current; at > from; at = shift(at, -1)) count++;
        long[] result = new long[count];
        long at = current;
        for (int i = count - 1; i >= 0; i--) {
            result[i] = at;
            at = shift(at, -1);
        }
        return result;
    }

    /** The time listened in each bar of {@link #starts()}, in milliseconds. */
    public long[] values(long[] starts) {
        long[] result = new long[starts.length];
        for (int i = 0; i < starts.length; i++) {
            Long sum = sums.get(starts[i]);
            result[i] = sum == null ? 0 : sum;
        }
        return result;
    }

    /** The start of the day, month or year of a moment. */
    long start(long time) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (unit != Unit.DAY) calendar.set(Calendar.DAY_OF_MONTH, 1);
        if (unit == Unit.YEAR) calendar.set(Calendar.MONTH, Calendar.JANUARY);
        return calendar.getTimeInMillis();
    }

    /** The start of the bar {@code bars} bars away. */
    private long shift(long barStart, int bars) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(barStart);
        calendar.add(unit.field, bars);
        return start(calendar.getTimeInMillis());
    }
}
