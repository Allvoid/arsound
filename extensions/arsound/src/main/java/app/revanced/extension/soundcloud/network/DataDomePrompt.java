package app.revanced.extension.soundcloud.network;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
        activity.runOnUiThread(() -> showPrompt(context, challenge, activity));
    }

    private static void showPrompt(Context context, Intent challenge, Activity activity) {
        Prompt prompt = new Prompt(context, challenge, activity);
        try {
            activity.getApplication().registerActivityLifecycleCallbacks(prompt);
            List<LocalSheet.Item> items = LocalSheet.items();
            items.add(new LocalSheet.Item(text("Пройти проверку", "Verify now"), "ic_actions_checkmark", prompt::verify));
            items.add(new LocalSheet.Item(text("Не сейчас", "Not now"), "ic_actions_close", prompt::postpone));
            Dialog dialog = LocalSheet.show(activity,
                    text("SoundCloud просит проверку", "SoundCloud asks for a check"),
                    text("Защита SoundCloud от ботов хочет убедиться, что вы не бот. Без проверки часть запросов "
                                    + "не пройдёт; скачанное и импортированное играет как обычно.",
                            "SoundCloud's bot protection wants to verify this device. Without it some requests fail; "
                                    + "downloaded and imported music plays as usual."),
                    items,
                    prompt::postpone);
            // The sheet could not be shown (the window is gone): nobody would ever answer the SDK.
            if (dialog == null) prompt.postpone();
            else prompt.dialog = dialog;
        } catch (Exception ex) {
            Logger.printException(() -> "DataDome: could not show the check prompt", ex);
            prompt.postpone();
        }
    }

    /**
     * One shown prompt. The SDK waits for exactly one answer and, if it never comes, holds every later challenged
     * request for good, so the answer is also given when the activity is destroyed with the sheet open: the sheet is
     * then removed without a dismiss, and neither the item nor the cancel callback runs.
     */
    private static final class Prompt implements Application.ActivityLifecycleCallbacks {
        private final Context context;
        private final Intent challenge;
        private final Activity activity;
        private final AtomicBoolean answered = new AtomicBoolean();
        volatile Dialog dialog;

        Prompt(Context context, Intent challenge, Activity activity) {
            this.context = context;
            this.challenge = challenge;
            this.activity = activity;
        }

        /** "Verify now": the check screen answers the SDK itself, in all its exits. */
        void verify() {
            stopWatching();
            answered.set(true);
            try {
                context.startActivity(challenge);
            } catch (Exception ex) {
                Logger.printException(() -> "DataDome: could not open the check screen", ex);
                answered.set(false);
                postpone();
            }
        }

        /** Tells the SDK the check was postponed, once, however the sheet ended. */
        void postpone() {
            stopWatching();
            if (answered.compareAndSet(false, true)) cancel(context, challenge);
        }

        private void stopWatching() {
            try {
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            } catch (Exception ex) {
                Logger.printException(() -> "DataDome: could not stop watching the screen", ex);
            }
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
            if (destroyed != activity) return;
            postpone();
            Dialog shown = dialog;
            if (shown == null) return;
            try {
                shown.dismiss();
            } catch (Exception ex) {
                Logger.printInfo(() -> "DataDome: the sheet was already gone: " + ex);
            }
        }

        @Override
        public void onActivityCreated(Activity created, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity started) {
        }

        @Override
        public void onActivityResumed(Activity resumed) {
        }

        @Override
        public void onActivityPaused(Activity paused) {
        }

        @Override
        public void onActivityStopped(Activity stopped) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity saved, Bundle outState) {
        }
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
