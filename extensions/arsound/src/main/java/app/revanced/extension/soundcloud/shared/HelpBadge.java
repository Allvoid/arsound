package app.revanced.extension.soundcloud.shared;

import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.permissions.WelcomePermissions;

/**
 * The small "?" in a circle next to a title. A tap opens a sheet with the explanation, in the same
 * look as the welcome screen. Used by the Arsound search switch and the Arsound settings.
 */
public final class HelpBadge {
    private HelpBadge() {
    }

    /**
     * @param description What the badge explains, for screen readers.
     * @param paragraphs  The explanation shown on tap.
     */
    public static TextView create(Context context, int color, String description, CharSequence... paragraphs) {
        TextView badge = new TextView(context);
        badge.setText("?");
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setIncludeFontPadding(false);
        badge.setContentDescription(description);
        badge.setOnClickListener(v -> show(context, paragraphs));
        setColor(badge, color);
        return badge;
    }

    /** Size and gap of the badge after a title in a horizontal row. */
    public static LinearLayout.LayoutParams layoutParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpTruncated(context, 17), dpTruncated(context, 17));
        params.leftMargin = dpTruncated(context, 6);
        return params;
    }

    public static void setColor(TextView badge, int color) {
        badge.setTextColor(color);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setStroke(dpTruncated(badge.getContext(), 1.2f), color);
        badge.setBackground(circle);
    }

    public static void show(Context context, CharSequence... paragraphs) {
        try {
            new ArsoundDialog(context)
                    .setView(WelcomePermissions.createDialogContent(context, paragraphs))
                    .setPositiveButton(text("Понятно", "Got it"), null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the help", ex);
        }
    }
}
