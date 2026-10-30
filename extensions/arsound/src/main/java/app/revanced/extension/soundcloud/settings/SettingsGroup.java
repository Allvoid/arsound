package app.revanced.extension.soundcloud.settings;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

/**
 * The card that holds a few related rows: a rounded, softly outlined panel with thin dividers between the rows.
 * The dividers start where the text starts, after the icon column. Rows that are hidden take their divider along,
 * because a {@link LinearLayout} skips the divider next to a gone child. Made by {@link SettingsPage}.
 */
final class SettingsGroup extends LinearLayout {
    private final SettingsUi ui;
    private boolean dividerSet;

    SettingsGroup(SettingsUi ui) {
        super(ui.activity);
        this.ui = ui;
        setOrientation(VERTICAL);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ui.cardFill());
        shape.setCornerRadius(ui.cardRadius());
        shape.setStroke(ui.dp(1), ui.cardStroke());
        setBackground(shape);
        // The rows' ripple and glow are cut at the rounded corners.
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(true);
        setShowDividers(SHOW_DIVIDER_MIDDLE);
    }

    /** The margins every card has on a page. */
    static LayoutParams pageParams(SettingsUi ui) {
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = ui.dp(16);
        params.bottomMargin = ui.dp(12);
        return params;
    }

    /** A card whose rows keep apart by their own spacing, with no lines between them. */
    SettingsGroup withoutDividers() {
        dividerSet = true;
        setShowDividers(SHOW_DIVIDER_NONE);
        return this;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (!dividerSet) {
            // Looks at the first row: with an icon the dividers start after it, without one at the usual margin.
            dividerSet = true;
            int inset = child instanceof SettingRow && ((SettingRow) child).hasIcon() ? 16 + 36 + 14 : 16;
            GradientDrawable line = new GradientDrawable();
            line.setColor(ui.dividerColor());
            line.setSize(1, Math.max(1, ui.dp(1) / 2));
            setDividerDrawable(new InsetDrawable(line, ui.dp(inset), 0, 0, 0));
        }
    }
}
