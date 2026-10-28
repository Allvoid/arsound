package app.revanced.extension.soundcloud.settings;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Makes the chips of the wallpaper screens: a pill with a word, filled with the accent when chosen and with the card
 * colour when not. A chip is at least 44 dp tall. Used by {@link WallhavenScreen} and {@link WallhavenFilterSheet}.
 */
final class WallpaperChip {
    static final int HEIGHT_DP = 44;

    private WallpaperChip() {
    }

    static TextView create(SettingsUi ui, String label, boolean selected, View.OnClickListener onClick) {
        TextView chip = ui.createText("H4.Primary", label);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setMinHeight(ui.dp(HEIGHT_DP));
        chip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        chip.setOnClickListener(onClick);
        style(ui, chip, selected);
        return chip;
    }

    /** Paints the chip as chosen or not. */
    static void style(SettingsUi ui, TextView chip, boolean selected) {
        int accent = ArsoundTheme.palette(ui.activity, "special");
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(ui.dp(100));
        pill.setColor(selected ? accent : ArsoundTheme.palette(ui.activity, "highlight"));
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), pill, null));
        chip.setTextColor(selected ? Colors.contrast(accent) : ArsoundTheme.palette(ui.activity, "primary"));
        chip.setSelected(selected);
    }
}
