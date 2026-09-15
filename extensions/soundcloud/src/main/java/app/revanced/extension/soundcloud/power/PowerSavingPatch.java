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

    /**
     * Event reports of bundled third-party SDKs (feature flag analytics and marketing), sent in
     * the background every few seconds while the app is open. Feature flag downloads are kept.
     */
    public static boolean isBackgroundReportBlocked(String host, String path) {
        if (!Settings.isPowerSavingEnabled()) return false;
        host = host.toLowerCase(java.util.Locale.US);
        if (host.equals("events.statsigapi.net")) return true;
        if (host.endsWith("prodregistryv2.org") && path.contains("log_event")) return true;
        return host.contains("moengage.com");
    }

    public static long inboxPollSeconds(long original) {
        return Settings.isPowerSavingEnabled() ? Math.max(original, INBOX_POLL_SECONDS) : original;
    }
}
