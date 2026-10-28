package app.revanced.extension.soundcloud.shared;

import android.app.Activity;

/** Questions about SoundCloud's screens that several features ask the same way. */
public final class Screens {
    private Screens() {
    }

    /** True for the main screen of the app: the one with the player bar and the tabs. */
    public static boolean isMainActivity(Activity activity) {
        return activity.getClass().getName().endsWith(".MainActivity");
    }
}
