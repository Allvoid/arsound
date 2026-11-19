package app.revanced.extension.soundcloud.network;

/**
 * The ways the app can look up site addresses, as stored in the settings. Has no Android parts, so it is tested on
 * the JVM.
 */
public final class DnsModes {
    public static final String AUTO = "auto";
    public static final String DOH = "doh";
    public static final String PLAIN = "plain";
    /** Only the DNS of the phone: the DNS put into the app is skipped, as if the option were off. */
    public static final String SYSTEM = "system";

    private DnsModes() {
    }

    /** Whether the stored value is one of the modes; anything else is read as {@link #AUTO}. */
    public static boolean isKnown(String stored) {
        return AUTO.equals(stored) || DOH.equals(stored) || PLAIN.equals(stored) || SYSTEM.equals(stored);
    }

    /**
     * Whether the chosen servers are asked at all. "System only" leaves every name to the phone, even with the custom
     * DNS switched on, so the mode can be tried without losing the server that is set up.
     */
    public static boolean asksCustomServers(boolean customDnsOn, String mode) {
        return customDnsOn && !SYSTEM.equals(mode);
    }
}
