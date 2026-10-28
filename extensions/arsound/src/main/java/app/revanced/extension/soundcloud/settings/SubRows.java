package app.revanced.extension.soundcloud.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.widget.LinearLayout;

import app.revanced.extension.soundcloud.shared.Colors;

/**
 * The options of a feature, shown under its switch only while the feature is on: the rows are indented and a thin
 * accent line runs along their left side. Pass it to SettingsUi.createToggleRow as the group; it hides and shows
 * itself with the switch. Made by SettingsUi.createSubRows.
 */
final class SubRows extends LinearLayout implements CardItem {
    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SettingsUi ui;

    SubRows(SettingsUi ui) {
        super(ui.activity);
        this.ui = ui;
        setOrientation(VERTICAL);
        setPadding(ui.dp(36), 0, 0, 0);
        setWillNotDraw(false);
        rail.setColor(Colors.withAlpha(ui.accent(), 0x55));
        setShowDividers(SHOW_DIVIDER_MIDDLE);
        GradientDrawable line = new GradientDrawable();
        line.setColor(ui.dividerColor());
        line.setSize(1, Math.max(1, ui.dp(1) / 2));
        setDividerDrawable(new InsetDrawable(line, ui.dp(16), 0, 0, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = ui.dp(2);
        float x = ui.dp(30);
        canvas.drawRoundRect(x, ui.dp(6), x + width, getHeight() - ui.dp(6), width, width, rail);
    }

    /** Shows or hides the options with the switch of the feature. */
    void follow(boolean on) {
        setVisibility(on ? View.VISIBLE : View.GONE);
    }
}
