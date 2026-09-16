package app.revanced.extension.soundcloud.network;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.local.LocalSheet;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * SoundCloud's bot protection (DataDome) sometimes asks to verify the device and opens a white
 * check screen over the app on its own. Instead, Arsound asks first. "Not now" answers the SDK the
 * same way as closing the check screen, so the waiting requests fail right away instead of hanging.
 */
@SuppressWarnings("unused")
public final class DataDomePrompt {
    private DataDomePrompt() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    /** Injection point. Replaces {@code context.startActivity(challengeIntent)} in the DataDome SDK. */
    public static void start(Context context, Intent challenge) {
        Activity activity = UpdateChecker.resumedActivity();
        if (!Settings.isDataDomePromptEnabled() || activity == null || activity.isFinishing()) {
            context.startActivity(challenge);
            return;
        }
        activity.runOnUiThread(() -> {
            List<LocalSheet.Item> items = LocalSheet.items();
            items.add(new LocalSheet.Item(text("Пройти проверку", "Verify now"), "ic_actions_checkmark",
                    () -> context.startActivity(challenge)));
            items.add(new LocalSheet.Item(text("Не сейчас", "Not now"), "ic_actions_close",
                    () -> cancel(context, challenge)));
            LocalSheet.show(activity,
                    text("SoundCloud просит проверку", "SoundCloud asks for a check"),
                    text("Защита SoundCloud от ботов хочет убедиться, что вы не бот. Без проверки часть запросов "
                                    + "не пройдёт; скачанное и импортированное играет как обычно.",
                            "SoundCloud's bot protection wants to verify this device. Without it some requests fail; "
                                    + "downloaded and imported music plays as usual."),
                    items,
                    () -> cancel(context, challenge));
        });
    }

    /** The same result the check screen sends when it is closed without passing. */
    private static void cancel(Context context, Intent challenge) {
        try {
            Class.forName("co.datadome.sdk.b", false, context.getClassLoader())
                    .getMethod("resetHandlingResponseInProgress").invoke(null);
        } catch (Exception ex) {
            Logger.printException(() -> "DataDome: could not reset the check state", ex);
        }
        try {
            Intent result = new Intent("co.datadome.sdk.CAPTCHA_RESULT")
                    .putExtra("captcha_result", 0)
                    .putExtra("captcha_url", challenge.getStringExtra("captcha_url"))
                    .putExtra("request_url", challenge.getStringExtra("request_url"));
            Class<?> manager = Class.forName("androidx.localbroadcastmanager.content.LocalBroadcastManager", false, context.getClassLoader());
            Object instance = manager.getMethod("a", Context.class).invoke(null, context);
            manager.getMethod("c", Intent.class).invoke(instance, result);
            Logger.printInfo(() -> "DataDome: check postponed by the user");
        } catch (Exception ex) {
            Logger.printException(() -> "DataDome: could not answer the check", ex);
        }
    }
}
