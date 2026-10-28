package app.revanced.extension.soundcloud.login;

import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.debug.LogExport;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * A "Save logs" pill in the top corner of the sign-in screens. Before the user is signed in the settings of Arsound
 * cannot be reached, and a sign-in that fails is exactly when the developer needs the logs.
 * <p>
 * It sits over the whole sign-in activity, not in its layout: the layout differs between phones and tablets, and
 * the pill needs no part of it.
 */
public final class LoginLogsButton {
    private static final String AUTHENTICATION_ACTIVITY = "com.soundcloud.android.onboarding.auth.AuthenticationActivity";
    private static final String TAG = "arsound_login_logs";

    private LoginLogsButton() {
    }

    /** Called when an activity comes to the screen. Only the sign-in activity gets the pill. */
    public static void onActivityResumed(Activity activity) {
        if (!AUTHENTICATION_ACTIVITY.equals(activity.getClass().getName())) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        // Resuming again (back from the browser) finds the pill already there.
        if (content == null || hasPill(content)) return;

        TextView pill = createPill(activity);
        int margin = dpTruncated(activity, 12);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.setMargins(margin, margin, margin, margin);
        content.addView(pill, params);
        // The window can be drawn under the status bar: the pill moves below it.
        content.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                moveBelowStatusBar(pill, view, margin));
    }

    private static boolean hasPill(ViewGroup content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            if (TAG.equals(content.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static TextView createPill(Activity activity) {
        TextView pill = new TextView(activity);
        pill.setTag(TAG);
        pill.setText(Lang.text("Сохранить логи", "Save logs"));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dpTruncated(activity, 40));
        pill.setPadding(dpTruncated(activity, 14), dpTruncated(activity, 8), dpTruncated(activity, 14), dpTruncated(activity, 8));
        // Dark with a light edge: the sign-in screen is dark, but the edge keeps the pill visible on any picture.
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99000000);
        background.setStroke(dpTruncated(activity, 1), 0x55FFFFFF);
        background.setCornerRadius(dpTruncated(activity, 20));
        pill.setBackground(background);
        // Above the drawer of the sign-in screen, whatever its elevation.
        pill.setElevation(dpTruncated(activity, 8));
        pill.setOnClickListener(v -> LogExport.save(activity, false));
        return pill;
    }

    /**
     * Keeps the pill under the status bar: by as much as the bar covers the top of the content. Setting the margin
     * lays out again, but the second time nothing changes, so it stops.
     */
    private static void moveBelowStatusBar(View pill, View content, int margin) {
        WindowInsets insets = content.getRootWindowInsets();
        if (insets == null) return;
        int[] position = new int[2];
        content.getLocationInWindow(position);
        int covered = Math.max(0, insets.getSystemWindowInsetTop() - position[1]);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pill.getLayoutParams();
        if (params.topMargin == margin + covered) return;
        params.topMargin = margin + covered;
        pill.setLayoutParams(params);
    }
}
