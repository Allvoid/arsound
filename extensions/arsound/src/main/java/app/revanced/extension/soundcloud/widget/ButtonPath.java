package app.revanced.extension.soundcloud.widget;

/**
 * Which way a tap on previous, next or pause of the player widget goes to the app: SoundCloud's own, a broadcast the
 * launcher sends, or through the app, a transparent screen that passes the same broadcast on from inside the app. No
 * Android here, so tests can run the rule.
 * <p>
 * Some firmwares (Xiaomi's HyperOS is the known one) let the launcher's broadcast die on the way, so the widget's
 * buttons do nothing, though a tap that opens a screen goes through. The way through the app is only for them; every
 * other phone keeps SoundCloud's own way, which is why the default is "auto".
 */
final class ButtonPath {
    /** The setting: one of these. */
    static final long AUTO = 0;
    static final long THROUGH_APP = 1;
    static final long SOUNDCLOUD = 2;

    private ButtonPath() {
    }

    /**
     * @param mode          The setting, {@link #AUTO}, {@link #THROUGH_APP} or {@link #SOUNDCLOUD}.
     * @param xiaomi        Whether the firmware is Xiaomi's (HyperOS or MIUI).
     * @param nativeWorked  Whether a tap ever arrived the way SoundCloud sends it on this phone: then it works here.
     * @return True when the buttons should go through the app.
     */
    static boolean throughApp(long mode, boolean xiaomi, boolean nativeWorked) {
        if (mode == THROUGH_APP) return true;
        if (mode == SOUNDCLOUD) return false;
        return xiaomi && !nativeWorked;
    }

    /** The name of the way, for the log. */
    static String describe(long mode, boolean xiaomi, boolean nativeWorked) {
        String path = throughApp(mode, xiaomi, nativeWorked) ? "activity" : "broadcast";
        String why = mode == THROUGH_APP ? "chosen: through the app"
                : mode == SOUNDCLOUD ? "chosen: SoundCloud's own"
                : xiaomi ? (nativeWorked ? "auto: Xiaomi, but a broadcast tap arrived before" : "auto: Xiaomi firmware")
                : "auto: not Xiaomi";
        return path + " (" + why + ")";
    }
}
