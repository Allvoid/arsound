package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

import app.revanced.extension.shared.Logger;

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

    private MusicAccess() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    public static String permission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    public static boolean isGranted(Context context) {
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Called when an activity comes to the screen. Asks on the main screen, once per launch at most. */
    public static void onActivityResumed(Activity activity) {
        if (shownThisLaunch || !activity.getClass().getName().endsWith(".MainActivity")) return;
        // The greeting already asks for this permission.
        if (app.revanced.extension.soundcloud.permissions.WelcomePermissions.isShownThisLaunch()) return;
        if (isGranted(activity) || DownloadTrackPatch.getDownloadedTrackIds().isEmpty()) return;

        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        // Asked before and refused: ask again only if a downloaded file is known to be unreadable.
        if (preferences.getBoolean(ASKED, false) && !DownloadTrackPatch.hasUnreadableDownloads()) return;
        shownThisLaunch = true;

        try {
            new AlertDialog.Builder(activity)
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
