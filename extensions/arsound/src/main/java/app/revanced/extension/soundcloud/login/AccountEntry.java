package app.revanced.extension.soundcloud.login;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Where the list of accounts is reached on SoundCloud's own screens:
 * <ul>
 * <li>a long press on the round profile button at the start of the top bar (the user's picture), as in other apps;</li>
 * <li>on the sign-in screen, while accounts are kept, a "My accounts" pill: a tap signs in with one of them, so "Add
 * account" can always be gone back from.</li>
 * </ul>
 * Arsound's settings (Account) and SoundCloud's settings have a row too. Screen hook: {@link #onActivityResumed}.
 */
public final class AccountEntry {
    private static final String MAIN_ACTIVITY = "com.soundcloud.android.ui.main.MainActivity";
    private static final String AUTHENTICATION_ACTIVITY = "com.soundcloud.android.onboarding.auth.AuthenticationActivity";
    private static final String WATCH_TAG = "arsound_accounts_watch";
    private static final String PILL_TAG = "arsound_accounts_pill";
    /** The profile buttons are looked for at most this often while screens are laid out. */
    private static final long SCAN_EVERY_MS = 400;

    private AccountEntry() {
    }

    /** Screen hook, on every activity that comes to the screen. */
    public static void onActivityResumed(Activity activity) {
        try {
            String name = activity.getClass().getName();
            if (MAIN_ACTIVITY.equals(name)) onMain(activity);
            else if (AUTHENTICATION_ACTIVITY.equals(name)) onSignIn(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: screen hook failed", ex);
        }
    }

    // region Main screen: the profile button

    private static void onMain(Activity activity) {
        // The kept copy follows the tokens SoundCloud renews, once the list is in use.
        Utils.runOnBackgroundThread(() -> {
            if (!AccountVault.isEmpty(activity)) AccountSwitch.snapshotCurrent(activity);
        });
        View decor = activity.getWindow().getDecorView();
        if (WATCH_TAG.equals(decor.getTag(tagKey(activity)))) {
            attach(activity, decor);
            return;
        }
        decor.setTag(tagKey(activity), WATCH_TAG);
        long[] last = {0};
        Runnable scan = () -> attach(activity, decor);
        // Every tab and page has its own top bar, made when it is first opened: they are found as they appear.
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                long now = SystemClock.uptimeMillis();
                decor.removeCallbacks(scan);
                if (now - last[0] >= SCAN_EVERY_MS) {
                    last[0] = now;
                    attach(activity, decor);
                } else {
                    decor.postDelayed(scan, SCAN_EVERY_MS);
                }
            }
        });
        attach(activity, decor);
    }

    /** A key of our own for view tags: the id of a resource of the app that nothing else tags with. */
    private static int tagKey(Activity activity) {
        int id = AppResources.identifier(activity, "accessibility_open_profile", "string");
        return id != 0 ? id : android.R.id.content;
    }

    private static void attach(Activity activity, View decor) {
        String description = profileDescription(activity);
        if (description == null) return;
        ArrayList<View> found = new ArrayList<>();
        decor.findViewsWithText(found, description, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View button : found) {
            if (button.getTag(tagKey(activity)) != null) continue;
            button.setTag(tagKey(activity), WATCH_TAG);
            button.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                keepPicture(activity, v);
                AccountSheet.show(activity);
                return true;
            });
        }
    }

    private static String profileDescription(Activity activity) {
        int id = AppResources.identifier(activity, "accessibility_open_profile", "string");
        return id == 0 ? null : activity.getString(id);
    }

    /** The profile button shows the signed-in user's picture: a copy is kept for the list. */
    private static void keepPicture(Activity activity, View button) {
        if (!(button instanceof ImageView)) return;
        android.graphics.drawable.Drawable picture = ((ImageView) button).getDrawable();
        if (picture == null) return;
        Utils.runOnBackgroundThread(() -> {
            SavedAccount current = AccountSwitch.snapshotCurrent(activity);
            if (current != null) AccountAvatars.capture(activity, current, picture);
        });
    }

    // endregion

    // region Sign-in screen: the kept accounts

    private static void onSignIn(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Utils.runOnBackgroundThread(() -> {
            List<SavedAccount> accounts = AccountVault.list(activity);
            boolean signedIn = AccountSwitch.current(activity) != null;
            activity.runOnUiThread(() -> {
                View old = content.findViewWithTag(PILL_TAG);
                if (accounts.isEmpty() || signedIn) {
                    if (old != null) content.removeView(old);
                    return;
                }
                if (old == null) addPill(activity, content);
            });
        });
    }

    private static void addPill(Activity activity, ViewGroup content) {
        TextView pill = new TextView(activity);
        pill.setTag(PILL_TAG);
        pill.setText(Lang.text("Мои аккаунты", "My accounts"));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(activity, 40));
        pill.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        // As the "Save logs" pill of the other corner: dark with a light edge, seen on any picture.
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99000000);
        background.setStroke(dp(activity, 1), 0x55FFFFFF);
        background.setCornerRadius(dp(activity, 20));
        pill.setBackground(background);
        pill.setElevation(dp(activity, 8));
        pill.setOnClickListener(v -> showSaved(activity));
        int margin = dp(activity, 12);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        params.setMargins(margin, margin, margin, margin);
        content.addView(pill, params);
        content.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets == null) return;
            int[] position = new int[2];
            view.getLocationInWindow(position);
            int covered = Math.max(0, insets.getSystemWindowInsetTop() - position[1]);
            ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) pill.getLayoutParams();
            if (layout.topMargin == margin + covered) return;
            layout.topMargin = margin + covered;
            pill.setLayoutParams(layout);
        });
    }

    /** The kept accounts: a tap signs in with one. */
    private static void showSaved(Activity activity) {
        Utils.runOnBackgroundThread(() -> {
            List<SavedAccount> accounts = AccountVault.list(activity);
            activity.runOnUiThread(() -> {
                int primary = ArsoundTheme.palette(activity, "primary");
                int secondary = ArsoundTheme.palette(activity, "secondary");
                int accent = ArsoundTheme.palette(activity, "special");
                LinearLayout list = new LinearLayout(activity);
                list.setOrientation(LinearLayout.VERTICAL);
                Dialog[] shown = new Dialog[1];
                for (SavedAccount account : accounts) {
                    View row = AccountSheet.row(activity, AccountSheet.avatar(activity, account, accent), account.shownName(),
                            account.email != null ? account.email : Lang.text("Войти без пароля", "Sign in without a password"),
                            null, primary, secondary, accent);
                    row.setOnClickListener(v -> {
                        if (shown[0] != null) shown[0].dismiss();
                        AccountSwitch.signInSaved(activity, account);
                    });
                    list.addView(row);
                }
                shown[0] = new ArsoundDialog(activity)
                        .setTitle(Lang.text("Войти в сохранённый аккаунт", "Sign in to a kept account"))
                        .setView(list)
                        .setNegativeButton(Lang.text("Закрыть", "Close"), null)
                        .show();
            });
        });
    }

    // endregion

    private static int dp(Activity activity, float value) {
        return Dimens.dp(activity, value);
    }
}
