package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.permissions.WelcomePermissions;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Screens;

/**
 * Asks for access to music files, so downloaded tracks stay playable after the app is reinstalled.
 * <p>
 * Android gives an app access to the files it created itself. When Arsound is removed and installed
 * again (for example by ReVanced Manager), the tracks it downloaded before belong to nobody, and
 * without this permission they cannot be read: such tracks were streamed instead, and did not play
 * at all without a network.
 */
public final class MusicAccess {
    private static final String PREFERENCES_NAME = "arsound_music_access";
    private static final String ASKED = "asked";

    private static boolean shownThisLaunch;
    private static boolean checkedThisLaunch;

    private MusicAccess() {
    }

    public static String permission() {
        if (Build.VERSION.SDK_INT >= 33) return "android.permission.READ_MEDIA_AUDIO";
        // Before Android 10 a file is written into Music by its path, which needs the permission to write; the
        // permission to write also reads. (Only builds for old phones come to this: the app itself needs Android 12.)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "android.permission.WRITE_EXTERNAL_STORAGE";
        return "android.permission.READ_EXTERNAL_STORAGE";
    }

    public static boolean isGranted(Context context) {
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Called when an activity comes to the screen. Asks on the main screen, once per launch at most. */
    public static void onActivityResumed(Activity activity) {
        if (shownThisLaunch || !Screens.isMainActivity(activity)) return;
        // The greeting already asks for this permission.
        if (WelcomePermissions.isShownThisLaunch()) return;
        if (isGranted(activity) || DownloadTrackPatch.getDownloadedTrackIds().isEmpty()) return;

        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(ASKED, false)) {
            // Asked before and refused: ask again only if a downloaded file is known to be unreadable.
            // Files become unreadable by a reinstall, which restarts the process, so once per launch is
            // enough. The check looks at every downloaded file, so it runs off the main thread.
            if (checkedThisLaunch) return;
            checkedThisLaunch = true;
            Utils.runOnBackgroundThread(() -> {
                try {
                    if (!DownloadIndex.hasUnreadableDownloads()) return;
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not check downloaded files for access", ex);
                    return;
                }
                Utils.runOnMainThread(() -> {
                    // The screen may have closed, or access may have been given, while the files were checked.
                    if (shownThisLaunch || isGranted(activity)) return;
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        // Recreated (turned, dark mode): the new screen checks again.
                        checkedThisLaunch = false;
                        return;
                    }
                    ask(activity, preferences);
                });
            });
            return;
        }
        ask(activity, preferences);
    }

    private static void ask(Activity activity, SharedPreferences preferences) {
        shownThisLaunch = true;

        try {
            new ArsoundDialog(activity)
                    .setTitle(text("Доступ к скачанной музыке", "Access to downloaded music"))
                    .setMessage(text("Разрешите доступ к музыке, чтобы скачанные треки играли из файлов без интернета. "
                                    + "Без него треки, скачанные до переустановки Arsound, играют только из сети.",
                            "Allow access to music so downloaded tracks play from their files without a connection. "
                                    + "Without it, tracks downloaded before Arsound was reinstalled only play from the network."))
                    .setNegativeButton(text("Не сейчас", "Not now"), (dialog, which) -> preferences.edit().putBoolean(ASKED, true).apply())
                    .setPositiveButton(text("Разрешить", "Allow"), (dialog, which) -> {
                        preferences.edit().putBoolean(ASKED, true).apply();
                        activity.requestPermissions(new String[]{permission()}, 0x4152);
                    })
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not ask for music access", ex);
        }
    }
}
