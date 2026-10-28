package app.revanced.extension.soundcloud.player;

/**
 * One of the two ways to set the sleep timer: "in 30 minutes" or "at 23:30". The window shows the same two wheels
 * for both and asks the mode what they mean. The mode works on one number, its <em>value</em>: the minutes to wait
 * for {@link InTimeMode}, the minute of the day for {@link AtClockMode}. No Android, so it runs in the JVM tests.
 */
interface TimerMode {
    /** The mode that was last used: the clock one if the flag says so. */
    static TimerMode of(boolean atClock) {
        return atClock ? new AtClockMode() : new InTimeMode();
    }

    /** What the tab of the mode says. */
    String tabLabel();

    /** Whether this is the clock mode; remembered between openings of the window. */
    boolean isAtClock();

    /** The key the last chosen value is kept under in the timer's preferences. */
    String preferenceKey();

    /** What the preferences hold under {@link #preferenceKey} when nothing was stored. */
    int missingValue();

    /** The value to open the wheels on. @param stored What the preferences held. */
    int initialValue(int stored, long nowMs);

    TimerWheel hoursWheel();

    TimerWheel minutesWheel();

    /** Where the wheels stand for a value. */
    int hoursIndex(int value);

    int minutesIndex(int value);

    /** The value the wheels show. */
    int value(int hoursIndex, int minutesIndex);

    /** Whether the value sets nothing yet, so the timer cannot start. */
    boolean isEmpty(int value);

    /** The moment the music stops for a value, in {@link System#currentTimeMillis()} terms. */
    long stopAt(int value, long nowMs);

    /** The line under the wheels: when the music stops, or what to choose if nothing is. */
    String summary(int value, long nowMs);

    /** Whether the quick choices (15, 30, 60, 120 minutes) belong to the mode. */
    boolean hasPresets();

    /** Whether the value is exactly the quick choice. */
    boolean isPreset(int value, int presetMinutes);

    /** Whether the wheels carry the labels "h" and "min" next to them; else a colon stands between them. */
    boolean labelsWheelsWithUnits();
}
