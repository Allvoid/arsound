package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;

/**
 * The choice of the mode of the custom theme: always dark, always light, or as the phone is. Three chips side by side,
 * each 48 dp tall with a short word, so none of them is cut off on a narrow screen; the chosen one is filled with the
 * accent. Used by {@link CustomThemeScreen}.
 */
final class ThemeModeChips extends LinearLayout {
    /** Told which mode the user picked. */
    interface Listener {
        void onMode(String mode);
    }

    private static final String[] MODES = {CustomTheme.MODE_DARK, CustomTheme.MODE_LIGHT, CustomTheme.MODE_AUTO};

    private final SettingsUi ui;
    private final TextView[] chips = new TextView[MODES.length];
    private String mode;

    ThemeModeChips(SettingsUi ui, String mode, Listener listener) {
        super(ui.activity);
        this.ui = ui;
        this.mode = mode;
        setOrientation(HORIZONTAL);
        String[] labels = {text("Тёмная", "Dark"), text("Светлая", "Light"), text("Авто", "Auto")};
        for (int i = 0; i < MODES.length; i++) {
            String value = MODES[i];
            TextView chip = ui.createText("H4.Primary", labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setMinHeight(ui.dp(48));
            chip.setOnClickListener(v -> {
                if (value.equals(this.mode)) return;
                setMode(value);
                listener.onMode(value);
            });
            chips[i] = chip;
            LayoutParams params = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = i == 0 ? 0 : ui.dp(8);
            addView(chip, params);
        }
        draw();
    }

    /** Marks a mode as chosen without telling the listener: for a change that came from elsewhere. */
    void setMode(String mode) {
        this.mode = mode;
        draw();
    }

    private void draw() {
        int accent = ArsoundTheme.palette(ui.activity, "special");
        for (int i = 0; i < chips.length; i++) {
            boolean chosen = MODES[i].equals(mode);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(ui.dp(100));
            pill.setColor(chosen ? accent : ArsoundTheme.palette(ui.activity, "highlight"));
            chips[i].setBackground(pill);
            chips[i].setTextColor(chosen ? Colors.contrast(accent) : ArsoundTheme.palette(ui.activity, "primary"));
            chips[i].setSelected(chosen);
        }
    }

    /** The short line that says what the chosen mode does. */
    static String explanation(String mode) {
        return CustomTheme.MODE_DARK.equals(mode)
                ? text("Всегда тёмное, что бы ни стояло на телефоне.", "Always dark, whatever the phone uses.")
                : CustomTheme.MODE_LIGHT.equals(mode)
                ? text("Всегда светлое, что бы ни стояло на телефоне.", "Always light, whatever the phone uses.")
                : text("Тёмное или светлое, как на телефоне; у каждого вида свой фон.", "Dark or light as the phone is; each look has its own background.");
    }
}
