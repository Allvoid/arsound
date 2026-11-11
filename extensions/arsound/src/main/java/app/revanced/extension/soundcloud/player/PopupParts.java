package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Colors.contrast;
import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * What the small windows of the player are made of, in the colours of the theme: a heading, a line of text, and a
 * row of chips of which one is chosen (filled with the accent). Used by {@link VersionsPopup} and
 * {@link TrackSpeedPanel}, so the two look the same.
 */
final class PopupParts {
    final Context context;
    final int accent, primary, secondary;

    PopupParts(Context context) {
        this.context = context;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
    }

    /** A small heading in the accent, in capitals. */
    TextView heading(String value) {
        TextView view = label(value, 13);
        view.setTextColor(accent);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAllCaps(true);
        view.setPadding(0, dp(14), 0, dp(6));
        return view;
    }

    TextView label(String value, int sp) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(primary);
        return view;
    }

    /** The faint rounded strip a row of chips lies on. */
    LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        row.setBackground(rounded(withAlpha(primary, 0x14), 14));
        return row;
    }

    TextView chip(String label, boolean on) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        paint(view, on);
        return view;
    }

    /** The chosen chip is filled with the accent, the others are plain. */
    void paint(TextView chip, boolean on) {
        chip.setBackground(on ? rounded(accent, 11) : null);
        chip.setTextColor(on ? contrast(accent) : primary);
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    int dp(float value) {
        return Dimens.dp(context, value);
    }
}
