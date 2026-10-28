package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.Calendar;

/** "At a time": the music stops when the clock shows the chosen time. The value is the minute of the day. */
final class AtClockMode implements TimerMode {
    private static final TimerWheel HOURS = new TimerWheel(0, 23, 1, true);
    private static final TimerWheel MINUTES = new TimerWheel(0, 59, 1, true);

    @Override
    public String tabLabel() {
        return text("Ко времени", "At a time");
    }

    @Override
    public boolean isAtClock() {
        return true;
    }

    @Override
    public String preferenceKey() {
        return "clock";
    }

    @Override
    public int missingValue() {
        return -1;
    }

    @Override
    public int initialValue(int stored, long nowMs) {
        if (stored >= 0) return stored;
        // An hour from now, rounded up to five minutes.
        Calendar later = Calendar.getInstance();
        later.setTimeInMillis(nowMs);
        later.add(Calendar.MINUTE, 60 + InTimeMode.MINUTE_STEP - later.get(Calendar.MINUTE) % InTimeMode.MINUTE_STEP);
        return later.get(Calendar.HOUR_OF_DAY) * 60 + later.get(Calendar.MINUTE);
    }

    @Override
    public TimerWheel hoursWheel() {
        return HOURS;
    }

    @Override
    public TimerWheel minutesWheel() {
        return MINUTES;
    }

    @Override
    public int hoursIndex(int value) {
        return value / 60;
    }

    @Override
    public int minutesIndex(int value) {
        return value % 60;
    }

    @Override
    public int value(int hoursIndex, int minutesIndex) {
        return hoursIndex * 60 + minutesIndex;
    }

    @Override
    public boolean isEmpty(int value) {
        return false;
    }

    @Override
    public long stopAt(int value, long nowMs) {
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(nowMs);
        at.set(Calendar.HOUR_OF_DAY, value / 60);
        at.set(Calendar.MINUTE, value % 60);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        // A time that has passed today means tomorrow.
        if (at.getTimeInMillis() <= nowMs) at.add(Calendar.DAY_OF_MONTH, 1);
        return at.getTimeInMillis();
    }

    @Override
    public String summary(int value, long nowMs) {
        long at = stopAt(value, nowMs);
        return text("Музыка остановится через ", "Music stops in ") + TimerFormat.duration(TimerFormat.minutesUntil(at, nowMs));
    }

    @Override
    public boolean hasPresets() {
        return false;
    }

    @Override
    public boolean isPreset(int value, int presetMinutes) {
        return false;
    }

    @Override
    public boolean labelsWheelsWithUnits() {
        return false;
    }
}
