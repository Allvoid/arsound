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

    private static volatile boolean playingFile;

    /**
     * Injection point. Called when SoundCloud's player starts a playback item.
     * Remembers whether it plays from a local file (downloaded by Arsound or imported).
     */
    public static void onPlaybackItem(Object player, Object item) {
        try {
            java.lang.reflect.Method stream = null;
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                if (method.getName().equals("k") && method.getParameterTypes().length == 1
                        && method.getReturnType().getName().endsWith(".Stream")) {
                    stream = method;
                    break;
                }
            }
            Object result = stream == null ? null : stream.invoke(player, item);
            playingFile = result != null && result.getClass().getName().endsWith("Stream$FileStream");
        } catch (Exception ex) {
            playingFile = false;
        }
    }

    /**
     * Injection point. The player keeps Wi-Fi awake while playing; a local file does not need the network.
     * The CPU wake lock is kept, so playback with the screen off is not affected.
     */
    public static boolean keepWifiAwake(boolean original) {
        return original && !(playingFile && Settings.isPowerSavingEnabled());
    }

    public static long inboxPollSeconds(long original) {
        return Settings.isPowerSavingEnabled() ? Math.max(original, INBOX_POLL_SECONDS) : original;
    }
}
