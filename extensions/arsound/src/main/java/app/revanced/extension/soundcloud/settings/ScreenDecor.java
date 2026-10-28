package app.revanced.extension.soundcloud.settings;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** The decoration the chosen theme gives the settings screens: a badge behind the logo, a glow, colour strips. */
final class ScreenDecor {
    private final SettingsUi ui;
    /** The decoration of the chosen theme, or null. */
    private final JSONObject decor = ArsoundTheme.decor(Utils.getContext());

    ScreenDecor(SettingsUi ui) {
        this.ui = ui;
    }

    /** A theme may put the letter on a rounded tile of its accent; 0 for none. */
    int badgeColor() {
        return ArsoundTheme.decorColor(decor, "settingsBadge", 0);
    }

    /** A theme may light the top of the main screen with its colour, fading into the background; 0 for none. */
    int glowColor() {
        return ArsoundTheme.decorColor(decor, "settingsGlow", 0);
    }

    private JSONArray strips() {
        JSONArray strips = decor == null ? null : decor.optJSONArray("settingsStrips");
        return strips == null || strips.length() == 0 ? null : strips;
    }

    boolean hasStrips() {
        return strips() != null;
    }

    /** The colour of the {@code index}-th strip; the colours repeat. Only while {@link #hasStrips}. */
    int stripColor(int index) {
        JSONArray strips = strips();
        return ArsoundTheme.color(strips.optString(index % strips.length()));
    }

    /** Puts a strip beside every card of the list from {@code firstRow} on; headings and notes get none. */
    void addStrips(LinearLayout list, int firstRow) {
        if (!hasStrips()) return;
        int stripped = 0;
        for (int i = firstRow; i < list.getChildCount(); i++) {
            View card = list.getChildAt(i);
            if (!(card instanceof SettingsGroup)) continue;
            list.removeViewAt(i);
            list.addView(withStrip(card, stripColor(stripped++)), i);
        }
    }

    /** The row or card with a coloured strip along its left edge; its own margins are kept. */
    View withStrip(View row, int color) {
        LinearLayout line = new LinearLayout(ui.activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        View strip = new View(ui.activity);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(ui.dp(2));
        strip.setBackground(shape);
        boolean card = row instanceof SettingsGroup;
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(ui.dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
        stripParams.leftMargin = ui.dp(card ? 10 : 16);
        stripParams.topMargin = ui.dp(card ? 4 : 8);
        stripParams.bottomMargin = ui.dp(card ? 16 : 8);
        line.addView(strip, stripParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (card) {
            rowParams.leftMargin = ui.dp(6);
            rowParams.rightMargin = ui.dp(16);
            rowParams.bottomMargin = ui.dp(12);
        }
        line.addView(row, rowParams);
        return line;
    }
}
