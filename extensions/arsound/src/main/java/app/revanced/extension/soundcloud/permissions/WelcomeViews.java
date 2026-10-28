package app.revanced.extension.soundcloud.permissions;

import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The small building blocks the greeting and its language switch are drawn from. */
final class WelcomeViews {
    private WelcomeViews() {
    }

    static TextView textView(Context context, CharSequence value, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    static TextView colored(TextView view, int color) {
        view.setTextColor(color);
        return view;
    }

    static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dpTruncated(context, radius));
        shape.setColor(color);
        return shape;
    }

    /** A full-width row with this gap above it. */
    static LinearLayout.LayoutParams margins(Context context, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpTruncated(context, top);
        return params;
    }
}
