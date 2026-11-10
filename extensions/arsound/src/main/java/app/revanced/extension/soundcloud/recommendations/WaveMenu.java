package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The small menu that opens from the "⋮" of the wave in the "My Wave" tab: a rounded plate with rows, each with its
 * own icon. It grows out of the corner next to the button (a short scale and fade) and closes on a tap outside or on a
 * row. Under battery saving and with the system animations off it just appears.
 */
final class WaveMenu {
    private WaveMenu() {
    }

    /** One row: its icon (a drawable of the app by name), its words and what a tap does. */
    static final class Row {
        final String icon;
        final String label;
        final Runnable action;

        Row(String icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }
    }

    static void show(View anchor, Row... rows) {
        Context context = anchor.getContext();
        int primary = ArsoundTheme.palette(context, "primary");
        int surface = ArsoundTheme.palette(context, "surface");

        LinearLayout plate = new LinearLayout(context);
        plate.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Colors.blend(surface, primary, 0.1f) | 0xFF000000);
        shape.setCornerRadius(Dimens.dpExact(context, 16));
        shape.setStroke(Dimens.dp(context, 1), Colors.withAlpha(primary, 0x24));
        plate.setBackground(shape);
        plate.setElevation(Dimens.dpExact(context, 8));
        int pad = Dimens.dp(context, 6);
        plate.setPadding(0, pad, 0, pad);

        PopupWindow popup = new PopupWindow(plate, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(0));
        popup.setOutsideTouchable(true);

        for (Row row : rows) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(Dimens.dp(context, 48));
            line.setPadding(Dimens.dp(context, 16), 0, Dimens.dp(context, 20), 0);
            line.setBackground(new RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(primary, 0x2A)), null,
                    new ColorDrawable(0xFFFFFFFF)));
            line.setClickable(true);
            line.setFocusable(true);
            int id = Utils.getResourceIdentifier(ResourceType.DRAWABLE, row.icon);
            if (id != 0) {
                ImageView icon = new ImageView(context);
                icon.setImageResource(id);
                icon.setImageTintList(ColorStateList.valueOf(primary));
                int size = Dimens.dp(context, 24);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
                iconParams.rightMargin = Dimens.dp(context, 16);
                line.addView(icon, iconParams);
            }
            TextView label = new TextView(context);
            label.setText(row.label);
            label.setTextColor(primary);
            label.setTextSize(15);
            line.addView(label);
            line.setOnClickListener(v -> {
                popup.dismiss();
                row.action.run();
            });
            plate.addView(line);
        }

        plate.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Right-aligned under the button, as menus of the app open from a "⋮".
        popup.showAsDropDown(anchor, anchor.getWidth() - plate.getMeasuredWidth(), -Dimens.dp(context, 4));
        if (app.revanced.extension.soundcloud.theme.ThemeEffect.isStill(context)) return;
        plate.setPivotX(plate.getMeasuredWidth());
        plate.setPivotY(0f);
        plate.setScaleX(0.85f);
        plate.setScaleY(0.85f);
        plate.setAlpha(0f);
        plate.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start();
    }
}
