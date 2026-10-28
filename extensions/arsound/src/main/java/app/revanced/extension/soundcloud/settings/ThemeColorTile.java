package app.revanced.extension.soundcloud.settings;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A tile of {@link CustomThemeScreen} for one colour of the theme: a disc in the colour, its name and its hex code.
 * A tap opens the {@link ColorPickerSheet}; while the sheet is open the tile has an accent edge, so it is clear which
 * colour the sheet changes. At least 88 dp tall, so a thumb hits it easily.
 */
final class ThemeColorTile extends LinearLayout {
    private final SettingsUi ui;
    private final View disc;
    private final TextView code;
    private final String name;

    ThemeColorTile(SettingsUi ui, String name, View.OnClickListener onClick) {
        super(ui.activity);
        this.ui = ui;
        this.name = name;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setMinimumHeight(ui.dp(92));
        setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(10));
        setOnClickListener(onClick);

        disc = new View(ui.activity);
        addView(disc, new LayoutParams(ui.dp(40), ui.dp(40)));
        TextView title = ui.createText("H4.Primary", name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ui.dp(8), 0, 0);
        addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        code = ui.createText("Body.Secondary", "");
        code.setSingleLine(true);
        code.setGravity(Gravity.CENTER);
        addView(code, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * @param hex    The colour, #RRGGBB.
     * @param active Whether the picker for this colour is open.
     */
    void show(String hex, boolean active) {
        int accent = ArsoundTheme.palette(ui.activity, "special");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor(hex));
        circle.setStroke(Math.max(1, ui.dp(1)), 0x55808080);
        disc.setBackground(circle);

        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(ui.dp(18));
        plate.setColor(ArsoundTheme.palette(ui.activity, "highlight"));
        if (active) plate.setStroke(ui.dp(2), accent);
        setBackground(plate);
        code.setText(hex);
        setContentDescription(name + " " + hex);
    }
}
