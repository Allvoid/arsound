package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;

import app.revanced.extension.shared.Logger;

/**
 * The music player in the control centre and on the lock screen of vivo phones with OriginOS ("Origin Player",
 * com.vivo.musicwidgetmix) shows only the apps of its own list of package names. SoundCloud and Arsound are not on
 * it, so the player says "Not playing" while Arsound plays.
 * <p>
 * The list is the secure setting "music_app_white_list" (the player's built-in list when the setting is absent), see
 * {@link VivoPlayerList}. An app may write secure settings only with WRITE_SECURE_SETTINGS, which only adb can grant.
 * So Arsound shows a command for a computer ({@link VivoHintScreen}): it grants the permission, wakes
 * {@link VivoMusicCardReceiver}, which adds Arsound to the list, and restarts the player so it reads the list again.
 * After that Arsound checks on every start ({@link VivoHintScheduler}) that it is still on the list, because a vivo
 * update or cloud config can replace it.
 */
public final class VivoMusicCard {
    private static final String PLAYER_PACKAGE = "com.vivo.musicwidgetmix";
    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private static final String DESCRIBE_MISSING_RU = "Плеер в шторке не видит Arsound. Нажмите, чтобы узнать, как его добавить.";
    private static final String DESCRIBE_MISSING_EN = "The control centre player does not see Arsound. Tap to see how to add it.";

    public enum State {
        /** Not a phone with Origin Player. */
        NOT_VIVO(DESCRIBE_MISSING_RU, DESCRIBE_MISSING_EN),
        /** Arsound is on the player's list. */
        LISTED("Arsound в списке плеера. Если плеер его всё равно не показывает — перезагрузите телефон.",
                "Arsound is on the player's list. If the player still does not show it, restart the phone."),
        /** Arsound is not on the list. */
        NOT_LISTED(DESCRIBE_MISSING_RU, DESCRIBE_MISSING_EN),
        /** Android did not let Arsound read the list. */
        UNREADABLE("Не удалось прочитать список плеера. Нажмите, чтобы посмотреть, как добавить Arsound.",
                "Could not read the player's list. Tap to see how to add Arsound.");

        private final String russian;
        private final String english;

        State(String russian, String english) {
            this.russian = russian;
            this.english = english;
        }

        /** A short line for the settings: whether the player sees Arsound. */
        String describe() {
            return text(russian, english);
        }
    }

    private VivoMusicCard() {
    }

    /** True on a phone with Origin Player, or where its list exists. */
    public static boolean isSupported(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PLAYER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            try {
                return VivoPlayerList.stored(context) != null;
            } catch (RuntimeException readEx) {
                return false;
            }
        }
    }

    public static boolean canWrite(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static State state(Context context) {
        if (!isSupported(context)) return State.NOT_VIVO;
        try {
            // Without the setting the player uses its built-in list, which has the package of the Chinese build.
            JSONArray entries = VivoPlayerList.parse(VivoPlayerList.stored(context));
            return VivoPlayerList.contains(entries, context.getPackageName()) ? State.LISTED : State.NOT_LISTED;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the vivo music player list", ex);
            return State.UNREADABLE;
        }
    }

    /**
     * Adds Arsound to the player's list, keeping every app already on it. Needs WRITE_SECURE_SETTINGS.
     *
     * @return What the list holds now.
     */
    public static State addToList(Context context) {
        if (!isSupported(context)) return State.NOT_VIVO;
        try {
            String current = VivoPlayerList.stored(context);
            JSONArray list = VivoPlayerList.parse(current);
            if (VivoPlayerList.contains(list, context.getPackageName())) return State.LISTED;

            // Not a "cooperate" app: the player controls it through its media session, like any other.
            list.put(VivoPlayerList.entry(context.getPackageName(), "Arsound", false, true));
            VivoPlayerList.store(context, list);
            Logger.printInfo(() -> "Added Arsound to the vivo music player list"
                    + (current == null ? " (with the built-in list)" : ""));
            return state(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add Arsound to the vivo music player list", ex);
            return State.UNREADABLE;
        }
    }

    /** The command for a computer with adb: grants the permission, adds Arsound, restarts the player. */
    public static String adbCommand(Context context) {
        return "adb shell \"" + shellCommand(context) + "\"";
    }

    /** The same command for a terminal on the phone itself, such as aShell with Shizuku. */
    public static String shellCommand(Context context) {
        String packageName = context.getPackageName();
        return "pm grant " + packageName + " " + PERMISSION
                + " && am broadcast -n " + packageName + "/" + VivoMusicCardReceiver.class.getName()
                + " && am force-stop " + PLAYER_PACKAGE;
    }

    /**
     * Called when an activity comes to the screen. Once per launch on the main screen: keeps Arsound on the list
     * when it may write it, and otherwise shows how to add it; see {@link VivoHintScheduler}.
     */
    public static void onActivityResumed(Activity activity) {
        VivoHintScheduler.onActivityResumed(activity);
    }

    /** A short line for the settings: whether the player sees Arsound. */
    public static String describe(State state) {
        return state.describe();
    }

    /**
     * Explains why the player does not show Arsound and gives the command, which copies on a tap.
     * Its "Do not show again" box shows and changes whether the hint opens by itself on start.
     *
     * @param onClosed Runs when the dialog closes, may be null.
     */
    public static void showHelp(Activity activity, Runnable onClosed) {
        VivoHintScreen.show(activity, onClosed);
    }
}
