package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.LocaleList;
import android.provider.Settings;

import java.util.Arrays;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * The choice of the app language: as in the system, Russian, English, or the system's own list for the rest. Android 13+
 * keeps the language per app. One dialog for both places that offer it: Arsound's settings (Appearance) and the
 * "Language" row on SoundCloud's own "Basic settings" screen, where people look for it first.
 * <p>
 * SoundCloud's own row opens the system's page of app languages for {@code package:com.soundcloud.android}, the package
 * of the original app, not of Arsound: on a phone without the original it opened nothing, and with it, the wrong app's
 * languages. The row is sent here instead ({@code SettingsPatch}).
 */
public final class LanguageChoice {
    private static final String[] TAGS = {"", "ru", "en"};

    private LanguageChoice() {
    }

    /** Whether the app language can be set at all: Android 13 and later. */
    static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    static String[] labels() {
        return new String[]{text("Как в системе", "System default"), "Русский", "English"};
    }

    /** The index of the language chosen now in {@link #labels()}: 0 for the system's. */
    static int chosen(Activity activity) {
        LocaleManager manager = activity.getSystemService(LocaleManager.class);
        String language = manager.getApplicationLocales().isEmpty() ? "" : manager.getApplicationLocales().get(0).getLanguage();
        return Math.max(0, Arrays.asList(TAGS).indexOf(language));
    }

    /** Asks which language; a choice restarts the app, as Arsound reads the language once per start. */
    static void show(Activity activity) {
        if (!available()) return;
        LocaleManager manager = activity.getSystemService(LocaleManager.class);
        String[] labels = labels();
        String[] items = Arrays.copyOf(labels, labels.length + 1);
        items[labels.length] = text("Другие языки…", "Other languages…");
        new ArsoundDialog(activity)
                .setTitle(text("Язык приложения", "App language"))
                .setSingleChoiceItems(items, chosen(activity), (dialog, which) -> {
                    dialog.dismiss();
                    if (which >= labels.length) {
                        openSystemPage(activity);
                        return;
                    }
                    manager.setApplicationLocales(TAGS[which].isEmpty()
                            ? LocaleList.getEmptyLocaleList()
                            : LocaleList.forLanguageTags(TAGS[which]));
                    // Android redraws SoundCloud's screens by itself, but Arsound reads the language once per start:
                    // a restart switches everything.
                    ArsoundTheme.restartApp(activity);
                })
                .show();
    }

    /** The system's page of this app's languages (Arsound's package, whatever it is called on this phone). */
    private static void openSystemPage(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the system's app languages", ex);
        }
    }

    /**
     * Injection point, in the click of the "Language" row of SoundCloud's "Basic settings": in place of the system's
     * page for the original app. The argument is the settings fragment, which is not needed: the screen shown is.
     */
    public static void openFromSoundCloud(Object fragment) {
        try {
            Activity activity = UpdateChecker.resumedActivity();
            if (activity != null) show(activity);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not offer the app language", ex);
        }
    }
}
