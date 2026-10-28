package app.revanced.extension.soundcloud.player;

import android.app.Activity;
import android.content.Context;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * Decides when the vivo hint opens by itself: once per launch on the main screen, and only when it would not cover
 * another prompt.
 */
final class VivoHintScheduler {
    /** How often the hint checks whether the main screen is free, so the screen and its own prompts come first. */
    private static final long HINT_CHECK_MS = 1000;
    /** How many checks the hint makes; a screen still busy after them waits for the next launch. */
    private static final int HINT_CHECKS = 15;

    private static boolean checkedThisLaunch;

    private VivoHintScheduler() {
    }

    /**
     * Called when an activity comes to the screen. Once per launch on the main screen: keeps Arsound on the list
     * when it may write it, and otherwise shows how to add it, every launch until it is on the list or the user
     * ticks "Do not show again". It never covers another prompt: not in the launch of the greeting, and not while
     * any dialog is on screen; then it waits for the next launch.
     */
    static void onActivityResumed(Activity activity) {
        if (checkedThisLaunch || !activity.getClass().getName().endsWith(".MainActivity")) return;
        checkedThisLaunch = true;
        // The greeting asks for permissions and may switch the language; this launch belongs to it.
        if (app.revanced.extension.soundcloud.permissions.WelcomePermissions.isShownThisLaunch()) {
            Logger.printInfo(() -> "vivo hint waits: the greeting is shown in this launch");
            return;
        }
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            if (!VivoMusicCard.isSupported(context)) return;
            if (VivoMusicCard.canWrite(context)) {
                VivoMusicCard.addToList(context);
                return;
            }
            if (VivoHintScreen.isNeverShow(context)) return;
            VivoMusicCard.State state = VivoMusicCard.state(context);
            if (state != VivoMusicCard.State.NOT_LISTED) {
                Logger.printInfo(() -> "vivo hint not needed: " + state);
                return;
            }
            Utils.runOnMainThreadDelayed(() -> showWhenFree(activity, HINT_CHECKS), HINT_CHECK_MS);
        });
    }

    /**
     * Shows the hint once the main screen has the focus (it is loaded, and no Android dialog such as a permission
     * request is over it). An Arsound dialog on screen means another prompt of this launch: the hint waits for the
     * next launch.
     */
    private static void showWhenFree(Activity activity, int checksLeft) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (ArsoundDialog.isAnyShown()) {
            Logger.printInfo(() -> "vivo hint waits for the next launch: another Arsound dialog is on screen");
            return;
        }
        if (!activity.hasWindowFocus()) {
            if (checksLeft > 1) {
                Utils.runOnMainThreadDelayed(() -> showWhenFree(activity, checksLeft - 1), HINT_CHECK_MS);
            } else {
                Logger.printInfo(() -> "vivo hint waits for the next launch: the screen stayed busy");
            }
            return;
        }
        Logger.printInfo(() -> "vivo hint shown");
        VivoHintScreen.show(activity, null);
    }
}
