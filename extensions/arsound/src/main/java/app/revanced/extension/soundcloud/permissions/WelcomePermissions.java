package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.permissions.WelcomeLanguage.text;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
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

    /** Called when an activity comes to the screen. Shows the greeting once, on the main screen. */
    public static void onActivityResumed(Activity activity) {
        boolean mainScreen = Screens.isMainActivity(activity);
        if (mainScreen && LanguageChange.revealIfPending(activity)) return;
        if (shownThisLaunch || !mainScreen) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(SHOWN, false)) return;

        List<WelcomeItem> items = WelcomeItem.all();
        List<String> missing = new ArrayList<>();
        for (WelcomeItem item : items) if (!item.isGranted(activity)) missing.add(item.permission);
        shownThisLaunch = true;
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
                dialog.setPositiveButton(text("Понятно", "Got it"), (d, which) -> finish(activity, preferences));
            } else {
                dialog.setNegativeButton(text("Позже", "Later"), (d, which) -> finish(activity, preferences))
                        .setPositiveButton(text("Разрешить", "Allow"), (d, which) -> {
                            // Android shows its own window for each permission in turn.
                            activity.requestPermissions(missing.toArray(new String[0]), 0x4157);
                            finish(activity, preferences);
                        });
            }
            shown[0] = dialog.show();
        } catch (Exception ex) {
            WelcomeLanguage.clear();
            Logger.printException(() -> "Could not show the welcome", ex);
        }
    }

    private static void labelButtons(Dialog dialog, boolean askPermissions) {
        TextView positive = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_POSITIVE);
        if (positive != null) positive.setText(askPermissions ? text("Разрешить", "Allow") : text("Понятно", "Got it"));
        TextView negative = ArsoundDialog.getButton(dialog, DialogInterface.BUTTON_NEGATIVE);
        if (negative != null) negative.setText(text("Позже", "Later"));
    }

    /** The greeting is closed: the language picked on it becomes the app language. */
    private static void finish(Activity activity, SharedPreferences preferences) {
        preferences.edit().putBoolean(SHOWN, true).apply();
        LanguageChange.apply(activity, WelcomeLanguage.endWithRussian());
    }
}
