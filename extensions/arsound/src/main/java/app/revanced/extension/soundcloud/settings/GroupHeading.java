package app.revanced.extension.soundcloud.settings;

import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The small accent title above a card ("ТЕМА", "ОБОИ"). A {@link SettingsPage} starts a new card after it. Made by
 * SettingsUi.createSubHeading.
 */
final class GroupHeading extends TextView {
    GroupHeading(SettingsUi ui, String label) {
        super(ui.activity);
        ui.style(this, "H6.Special");
        setTextColor(ui.accent());
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        setGravity(Gravity.CENTER_VERTICAL);
        setText(label);
        setLetterSpacing(0.06f);
        // 20 above, 8 below, in line with the text of the cards (16 for the card's margin + 4).
        setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(8));
    }
}
