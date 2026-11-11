package app.revanced.extension.soundcloud.permissions;

/**
 * How long the greeting stays locked: its buttons do nothing until the person has spent {@link #LOCK_MS} in the app
 * with the greeting on screen. The time is spent only while the greeting is in front, so leaving the app, turning the
 * screen or switching the dark look does not restart it and does not skip it either.
 */
final class WelcomeLock {
    static final long LOCK_MS = 10_000;

    private static long remaining = LOCK_MS;

    private WelcomeLock() {
    }

    /** Counts a stretch of time in front of the greeting. */
    static void spend(long millis) {
        if (millis > 0) remaining = Math.max(0, remaining - millis);
    }

    static boolean isLocked() {
        return remaining > 0;
    }

    /** Whole seconds left, rounded up, so the label never shows 0 while the buttons are still locked. */
    static int secondsLeft() {
        return (int) ((remaining + 999) / 1000);
    }

    /** The text added to a button label while it is locked: " (7)"; empty when it is free. */
    static String suffix() {
        return isLocked() ? " (" + secondsLeft() + ")" : "";
    }

    /** For tests: a new launch starts the lock again. */
    static void reset() {
        remaining = LOCK_MS;
    }
}
