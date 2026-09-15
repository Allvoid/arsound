package app.revanced.extension.soundcloud.power;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Reduces background work that wakes the radio without a visible benefit.
 */
@SuppressWarnings("unused")
public final class PowerSavingPatch {
    /** Unread messages badge polling. SoundCloud checks every 30 seconds while any screen with a title bar is open. */
    private static final long INBOX_POLL_SECONDS = 300;

    private PowerSavingPatch() {
    }

    public static long inboxPollSeconds(long original) {
        return Settings.isPowerSavingEnabled() ? Math.max(original, INBOX_POLL_SECONDS) : original;
    }
}
