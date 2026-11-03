package app.revanced.extension.soundcloud.settings;

import android.widget.CompoundButton;

/**
 * A row with a switch at the end; the whole row is the tap target. The tile of the icon is lit while the switch is on.
 * Made by SettingsUi.createToggleRow.
 */
final class ToggleRow extends SettingRow {
    private CompoundButton toggle;

    ToggleRow(SettingsUi ui, String title, CharSequence summary) {
        super(ui, title, summary);
    }

    void attach(CompoundButton toggle) {
        this.toggle = toggle;
    }

    /**
     * Shows {@code checked} and hands every later change to the listener. The state is never saved by the view: it
     * always comes from the settings (see SettingsUi.createToggleRow).
     */
    void bind(CompoundButton button, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        attach(button);
        button.setSaveEnabled(false);
        button.setChecked(checked);
        setLit(checked);
        button.setOnCheckedChangeListener((view, now) -> {
            setLit(now);
            listener.onCheckedChanged(view, now);
        });
        setOnClickListener(v -> button.toggle());
    }

    /** Keeps the switch as it is: the row cannot be changed and looks it. */
    void lock() {
        if (toggle != null) toggle.setEnabled(false);
        setOnClickListener(null);
        setClickable(false);
        setAlpha(.6f);
    }

    /** Moves the switch without asking the user; the listener of the row hears it as any change. */
    void setChecked(boolean checked) {
        if (toggle != null) toggle.setChecked(checked);
    }
}
