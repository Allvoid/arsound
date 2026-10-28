package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/** "In time": the music stops after hours and minutes counted from now. The value is the minutes to wait. */
final class InTimeMode implements TimerMode {
    static final int MINUTE_STEP = 5;
    private static final int DEFAULT_MINUTES = 30;
    private static final TimerWheel HOURS = new TimerWheel(0, 12, 1, false);
    private static final TimerWheel MINUTES = new TimerWheel(0, 60 - MINUTE_STEP, MINUTE_STEP, false);

    @Override
    public String tabLabel() {
        return text("Через время", "In time");
    }

    @Override
    public boolean isAtClock() {
        return false;
    }

    @Override
    public String preferenceKey() {
        return "minutes";
    }

    @Override
    public int missingValue() {
        return DEFAULT_MINUTES;
    }

    @Override
    public int initialValue(int stored, long nowMs) {
        // Zero was remembered by older versions and would leave "Start" off on every opening.
        return stored <= 0 ? DEFAULT_MINUTES : stored;
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
        return value % 60 / MINUTE_STEP;
    }

    @Override
    public int value(int hoursIndex, int minutesIndex) {
        return hoursIndex * 60 + minutesIndex * MINUTE_STEP;
    }

    @Override
    public boolean isEmpty(int value) {
        return value == 0;
    }

    @Override
    public long stopAt(int value, long nowMs) {
        return nowMs + value * 60_000L;
    }

    @Override
    public String summary(int value, long nowMs) {
        if (isEmpty(value)) return text("Выберите, через сколько остановить музыку", "Choose when to stop the music");
        return text("Музыка остановится в ", "Music stops at ") + TimerFormat.clock(stopAt(value, nowMs));
    }

    @Override
    public boolean hasPresets() {
        return true;
    }

    @Override
    public boolean isPreset(int value, int presetMinutes) {
        return value == presetMinutes;
    }

    @Override
    public boolean labelsWheelsWithUnits() {
        return true;
    }
}
