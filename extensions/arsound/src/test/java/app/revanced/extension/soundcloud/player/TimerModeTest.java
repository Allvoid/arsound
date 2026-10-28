package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

public class TimerModeTest {
    private Locale saved;

    @Before
    public void useEnglish() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(saved);
    }

    private static long at(int hour, int minute) {
        Calendar time = Calendar.getInstance();
        time.set(2026, Calendar.OCTOBER, 28, hour, minute, 0);
        time.set(Calendar.MILLISECOND, 0);
        return time.getTimeInMillis();
    }

    @Test
    public void inTimeValueIsTheMinutesToWait() {
        TimerMode mode = new InTimeMode();
        assertEquals(75, mode.value(1, 3));
        assertEquals(1, mode.hoursIndex(75));
        assertEquals(3, mode.minutesIndex(75));
        assertEquals(at(12, 0) + 75 * 60_000L, mode.stopAt(75, at(12, 0)));
    }

    @Test
    public void inTimeStartsEmptyOnlyAtZero() {
        TimerMode mode = new InTimeMode();
        assertTrue(mode.isEmpty(0));
        assertFalse(mode.isEmpty(5));
        assertEquals("Choose when to stop the music", mode.summary(0, at(12, 0)));
    }

    @Test
    public void inTimeOpensOnThirtyMinutesWhenNothingUsefulWasStored() {
        TimerMode mode = new InTimeMode();
        assertEquals(30, mode.missingValue());
        assertEquals(30, mode.initialValue(0, 0));
        assertEquals(45, mode.initialValue(45, 0));
    }

    @Test
    public void inTimeSummaryNamesTheMomentTheMusicStops() {
        assertEquals("Music stops at 13:15", new InTimeMode().summary(75, at(12, 0)));
    }

    @Test
    public void atClockValueIsTheMinuteOfTheDay() {
        TimerMode mode = new AtClockMode();
        assertEquals(23 * 60 + 30, mode.value(23, 30));
        assertEquals(23, mode.hoursIndex(23 * 60 + 30));
        assertEquals(30, mode.minutesIndex(23 * 60 + 30));
        assertFalse(mode.isEmpty(0));
    }

    @Test
    public void atClockStopsTodayWhenTheTimeIsAhead() {
        assertEquals(at(23, 30), new AtClockMode().stopAt(23 * 60 + 30, at(22, 0)));
    }

    @Test
    public void atClockStopsTomorrowWhenTheTimeHasPassed() {
        long now = at(22, 0);
        long tomorrow = at(7, 0) + 24 * 3600_000L;
        assertEquals(tomorrow, new AtClockMode().stopAt(7 * 60, now));
        // The very moment counts as passed too.
        assertEquals(at(22, 0) + 24 * 3600_000L, new AtClockMode().stopAt(22 * 60, now));
    }

    @Test
    public void atClockOpensAnHourAheadRoundedUpToFiveMinutes() {
        assertEquals(13 * 60 + 5, new AtClockMode().initialValue(-1, at(12, 3)));
        assertEquals(13 * 60 + 5, new AtClockMode().initialValue(-1, at(12, 0)));
        assertEquals(10 * 60, new AtClockMode().initialValue(10 * 60, at(12, 3)));
    }

    @Test
    public void atClockSummaryNamesTheWait() {
        assertEquals("Music stops in 1 h 30 min", new AtClockMode().summary(13 * 60 + 30, at(12, 0)));
    }

    @Test
    public void onlyInTimeHasPresets() {
        assertTrue(new InTimeMode().hasPresets());
        assertTrue(new InTimeMode().isPreset(30, 30));
        assertFalse(new InTimeMode().isPreset(35, 30));
        assertFalse(new AtClockMode().hasPresets());
        assertFalse(new AtClockMode().isPreset(30, 30));
    }

    @Test
    public void modeIsChosenByTheStoredFlag() {
        assertFalse(TimerMode.of(false).isAtClock());
        assertTrue(TimerMode.of(true).isAtClock());
        assertEquals("minutes", TimerMode.of(false).preferenceKey());
        assertEquals("clock", TimerMode.of(true).preferenceKey());
    }

    @Test
    public void wheelLabels() {
        assertArrayEquals(new String[]{"0", "1", "2"}, new TimerWheel(0, 2, 1, false).labels());
        assertArrayEquals(new String[]{"00", "01"}, new TimerWheel(0, 1, 1, true).labels());
        assertEquals(12, new InTimeMode().minutesWheel().count());
        assertEquals("05", new InTimeMode().minutesWheel().labels()[1]);
        assertEquals(24, new AtClockMode().hoursWheel().count());
        assertEquals(60, new AtClockMode().minutesWheel().count());
    }

    @Test
    public void durationText() {
        assertEquals("45 min", TimerFormat.duration(45));
        assertEquals("2 h", TimerFormat.duration(120));
        assertEquals("1 h 15 min", TimerFormat.duration(75));
        assertEquals(1, TimerFormat.minutesUntil(1000, 1000));
        assertEquals(30, TimerFormat.minutesUntil(30 * 60_000L, 0));
    }

    @Test
    public void clockText() {
        assertEquals("7:05", TimerFormat.clock(at(7, 5)));
        assertEquals("23:30", TimerFormat.clock(at(23, 30)));
    }
}
