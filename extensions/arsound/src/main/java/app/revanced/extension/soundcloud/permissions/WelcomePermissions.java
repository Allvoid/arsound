package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.permissions.WelcomeLanguage.text;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Screens;

/**
 * Greets the user on the first launch: what the app is, where its settings are, and every permission before
 * Android asks for it — what it is for, and what stops working without it. Everything can be refused.
 * <p>
 * This class is the flow: when the greeting shows, what its buttons do, what happens when it closes. What it looks
 * like is {@link WelcomeContent} and {@link LanguageSwitch}; the language picked on it is {@link WelcomeLanguage}
 * and {@link LanguageChange}.
 */
public final class WelcomePermissions {
    private static final String PREFERENCES_NAME = "arsound_welcome";
    /** "_v2": the greeting with the way to the settings is shown once more to those who saw the old one. */
    private static final String SHOWN = "shown_v2";

    private static boolean shownThisLaunch;

    private WelcomePermissions() {
    }

    /** True while the greeting is on screen in this launch, so other prompts wait for the next launch. */
    public static boolean isShownThisLaunch() {
        return shownThisLaunch;
    }

    /** The Arsound logo and name, the header of Arsound dialogs. */
    public static LinearLayout createHeader(Context context) {
        return WelcomeContent.createHeader(context);
    }

    /** A dialog body in the style of the greeting: logo, then paragraphs. */
    public static ScrollView createDialogContent(Context context, CharSequence... paragraphs) {
        return WelcomeContent.createDialogContent(context, paragraphs);
    }

    /** The screen the greeting was shown on in this run. */
    private static java.lang.ref.WeakReference<Activity> greetingActivity = new java.lang.ref.WeakReference<>(null);

    /** Called when an activity comes to the screen. Shows the greeting once, on the main screen. */
    public static void onActivityResumed(Activity activity) {
        boolean mainScreen = Screens.isMainActivity(activity);
        if (mainScreen && LanguageChange.revealIfPending(activity)) return;
        // The greeting went away with its screen (a turn, a switch of the dark look) before a button was pressed: the
        // screen made again shows it again, instead of it coming back only at a later start.
        Activity greeted = greetingActivity.get();
        if (shownThisLaunch && mainScreen && greeted != activity && (greeted == null || greeted.isDestroyed())) {
            shownThisLaunch = false;
        }
        if (shownThisLaunch || !mainScreen) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(SHOWN, false)) return;

        List<WelcomeItem> items = WelcomeItem.all();
        List<String> missing = new ArrayList<>();
        for (WelcomeItem item : items) if (!item.isGranted(activity)) missing.add(item.permission);
        shownThisLaunch = true;
        greetingActivity = new java.lang.ref.WeakReference<>(activity);
        // The greeting starts in English; its switch turns it and then the whole app to Russian.
        WelcomeLanguage.begin();
        boolean askPermissions = !missing.isEmpty();

        try {
            FrameLayout holder = new FrameLayout(activity);
            holder.setTag(ArsoundDialog.KEEP_COLORS);
            holder.addView(WelcomeContent.createGreeting(activity, WelcomeItem.all(), askPermissions));
            Dialog[] shown = new Dialog[1];
            View languageSwitch = LanguageSwitch.create(activity, russian -> {
                WelcomeLanguage.pick(russian);
                // The text fades out, changes and fades back in; the buttons change with it.
                holder.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                    holder.removeAllViews();
                    holder.addView(WelcomeContent.createGreeting(activity, WelcomeItem.all(), askPermissions));
                    if (shown[0] != null) labelButtons(shown[0], askPermissions);
                    holder.animate().alpha(1f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                }).start();
            });

            ArsoundDialog dialog = new ArsoundDialog(activity)
                    .setView(holder)
                    .setButtonsStart(languageSwitch)
                    .setCancelable(false);
            if (!askPermissions) {
                // Everything is allowed already: the greeting only tells where the settings are.
                dialog.setPositiveButton(text("Продолжить", "Continue"), (d, which) -> finish(activity, preferences));
            } else {
                dialog.setNegativeButton(text("Позже", "Later"), (d, which) -> finish(activity, preferences))
                        .setPositiveButton(text("Разрешить", "Allow"), (d, which) -> {
                            // Android shows its own window for each permission in turn.
                            activity.requestPermissions(missing.toArray(new String[0]), 0x4157);
                            finish(activity, preferences);
                        });
            }
            shown[0] = dialog.show();
            lockButtons(shown[0], activity, askPermissions);
        } catch (Exception ex) {
            WelcomeLanguage.clear();
            // Not shown, so not marked as shown: the next return to the main screen tries again.
            shownThisLaunch = false;
            Logger.printException(() -> "Could not show the welcome", ex);
        }
    }

    private static void labelButtons(Dialog dialog, boolean askPermissions) {
        String suffix = WelcomeLock.suffix();
        boolean locked = WelcomeLock.isLocked();
        TextView positive = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setText((askPermissions ? text("Разрешить", "Allow") : text("Продолжить", "Continue")) + suffix);
            lock(positive, locked);
        }
        TextView negative = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setText(text("Позже", "Later") + suffix);
            lock(negative, locked);
        }
    }

    private static void lock(TextView button, boolean locked) {
        button.setEnabled(!locked);
        button.setAlpha(locked ? .45f : 1f);
    }

    /**
     * The buttons stay locked for {@link WelcomeLock#LOCK_MS} of time spent in front of the greeting, with the seconds
     * left in their labels. Nothing else closes it: it is not cancelable, and Back and a tap outside do nothing.
     */
    private static void lockButtons(Dialog dialog, Activity activity, boolean askPermissions) {
        labelButtons(dialog, askPermissions);
        if (!WelcomeLock.isLocked()) return;
        Handler handler = new Handler(Looper.getMainLooper());
        long[] last = {SystemClock.elapsedRealtime()};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                boolean inFront = dialog.isShowing() && dialog.getWindow() != null
                        && dialog.getWindow().getDecorView().hasWindowFocus();
                if (inFront) WelcomeLock.spend(now - last[0]);
                last[0] = now;
                if (!dialog.isShowing()) return; // the screen went away; the next greeting goes on from what is left
                labelButtons(dialog, askPermissions);
                if (WelcomeLock.isLocked()) handler.postDelayed(this, 250);
            }
        };
        handler.postDelayed(tick, 250);
    }

    /** The greeting is closed: the language picked on it becomes the app language. */
    private static void finish(Activity activity, SharedPreferences preferences) {
        preferences.edit().putBoolean(SHOWN, true).apply();
        LanguageChange.apply(activity, WelcomeLanguage.endWithRussian());
    }
}
