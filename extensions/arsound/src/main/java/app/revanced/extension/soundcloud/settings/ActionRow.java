package app.revanced.extension.soundcloud.settings;

import android.view.View;

import app.revanced.extension.soundcloud.shared.HelpBadge;

/**
 * A row you tap: it opens a screen, a dialog or runs an action. The look is in {@link SettingRow}; the summary
 * ({@link #description()}) is what screens change while they count, load or react. Made by SettingsUi.createActionRow.
 */
final class ActionRow extends SettingRow {
    /** @param help A {@link HelpBadge} shown right after the title, or null. */
    ActionRow(SettingsUi ui, String title, CharSequence summary, View help) {
        super(ui, title, summary);
        if (help != null) titleLine().addView(help, HelpBadge.layoutParams(ui.activity));
    }
}
