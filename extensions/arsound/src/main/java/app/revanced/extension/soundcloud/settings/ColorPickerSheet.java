package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;

/**
 * The sheet that picks one colour of the custom theme. It rises from the bottom and leaves the top of the screen free,
 * so the drawing of the app on {@link CustomThemeScreen} follows the finger live ({@link Listener#onLive}).
 * <p>
 * From top to bottom: the name of the colour with "Cancel" and "Done"; the colour as a disc and a hex field; the
 * {@link HsvColorPicker}, showing the part of the square that suits the colour ({@link ColorRange}); a note when the
 * theme had to change the colour; the colours picked lately ({@link RecentColours}); the named ready colours in a grid.
 * <p>
 * "Done", a tap outside and the back button keep the colour; "Cancel" gives the old colour back.
 */
final class ColorPickerSheet {
    /** What the screen does while a colour is picked. */
    interface Listener {
        /** The colour changes, at every step of the finger. */
        void onLive(String hex);

        /** The colour is kept. */
        void onDone(String hex);

        /** The user gave the colour up: the screen goes back to the colour it had. */
        void onCancel();

        /** A warning when the theme changes this colour to keep the app readable, else null. */
        String note(String hex);
    }

    private final Activity activity;
    private final SettingsUi ui;
    private final Listener listener;
    private final ColorPreset[] presets;
    private final String initial;
    private final HsvColorPicker picker;
    private final EditText field;
    private final View dot;
    private final TextView badge;
    private final TextView note;
    private final TextView presetsLabel;
    private SwatchGrid recentGrid;
    private SwatchGrid presetGrid;
    private String current;
    private boolean writingField;
    private boolean ready;
    private boolean finished;
    /** Whether the note was shown by itself once; after that the badge opens it. */
    private boolean badgeHinted;
    private ThemeSheet sheet;

    private ColorPickerSheet(SettingsUi ui, String initial, ColorPreset[] presets, ColorRange range, Listener listener) {
        this.ui = ui;
        this.activity = ui.activity;
        this.listener = listener;
        this.presets = presets;
        this.initial = initial;
        this.current = initial;
        picker = new HsvColorPicker(activity);
        picker.setRange(range);
        picker.setContentDescription(text("Выбор цвета", "Colour picker"));
        field = new EditText(activity);
        dot = new View(activity);
        badge = ui.createText("Body.Secondary", text("подправлен", "adjusted"));
        note = ui.createText("Body.Secondary", "");
        presetsLabel = ui.createText("H4.Secondary", "");
    }

    /**
     * @param title   The name of the colour.
     * @param initial The colour now, #RRGGBB.
     * @param presets The named ready colours.
     * @param range   The part of the colour square to show.
     */
    static void show(SettingsUi ui, String title, String initial, ColorPreset[] presets, ColorRange range, Listener listener) {
        ColorPickerSheet sheet = new ColorPickerSheet(ui, CustomTheme.hex(initial, "#FF5500"), presets, range, listener);
        sheet.sheet = new ThemeSheet(ui.activity)
                .setContent(sheet.build(title))
                .setDim(0f)
                .setMaxHeight(.6f)
                .setOnDismiss(sheet::closed);
        sheet.sheet.show();
    }

    private View build(String title) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(8));

        content.addView(createHeader(title));
        content.addView(createColourRow());

        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickerParams.topMargin = dp(4);
        content.addView(picker, pickerParams);

        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(6), 0, 0);
        note.setVisibility(View.GONE);
        content.addView(note);

        List<String> recent = RecentColours.parse(Settings.getString(RecentColours.KEY, ""));
        if (!recent.isEmpty()) {
            content.addView(label(text("Недавние", "Recent")));
            recentGrid = new SwatchGrid(activity);
            for (String hex : recent) recentGrid.addView(swatch(hex, text("Недавний цвет", "Recent colour")));
            content.addView(recentGrid);
        }
        presetsLabel.setPadding(0, dp(10), 0, dp(2));
        content.addView(presetsLabel);
        presetGrid = new SwatchGrid(activity);
        for (ColorPreset preset : presets) presetGrid.addView(swatch(preset.hex, preset.name));
        content.addView(presetGrid);

        picker.setListener((color, fromUser) -> {
            current = hex(color);
            if (fromUser) writeField(current);
            refresh();
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (writingField) return;
                String typed = s.toString().replace("#", "");
                String valid = CustomTheme.hex(typed, null);
                if (typed.length() == 6 && valid != null) picker.setColor(Color.parseColor(valid));
            }
        });
        writeField(current);
        picker.setColor(Color.parseColor(current));
        ready = true;
        refresh();
        return content;
    }

    /** The name of the colour, then Cancel and Done. */
    private View createHeader(String title) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.createText("H3.Primary", title);
        name.setSingleLine(true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(text("Отмена", "Cancel"), false, v -> cancel()));
        header.addView(pill(text("Готово", "Done"), true, v -> done()));
        return header;
    }

    private View pill(String label, boolean main, View.OnClickListener onClick) {
        View button = ui.createPillButton(label, main, onClick);
        ((TextView) button).setMinHeight(dp(48));
        return button;
    }

    /** The disc of the colour, the hex field and the badge that says the theme changed the colour. */
    private View createColourRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        row.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36)));

        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7), (source, start, end, dest, ds, de) -> {
            StringBuilder kept = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c == '#' || CustomTheme.isHexDigit(c)) kept.append(Character.toUpperCase(c));
            }
            return kept.length() == end - start ? null : kept.toString();
        }});
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        field.setMinHeight(dp(48));
        field.setTextColor(ArsoundTheme.palette(activity, "primary"));
        int accent = ArsoundTheme.palette(activity, "special");
        field.setBackgroundTintList(ColorStateList.valueOf(accent));
        field.setHighlightColor((accent & 0x00FFFFFF) | 0x55000000);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fieldParams.leftMargin = dp(12);
        row.addView(field, fieldParams);

        badge.setTextColor(accent);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setVisibility(View.INVISIBLE);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(100));
        pill.setStroke(Math.max(1, dp(1)), (accent & 0x00FFFFFF) | 0x80000000);
        badge.setBackground(pill);
        badge.setOnClickListener(v -> note.setVisibility(note.getText().length() == 0 ? View.GONE : View.VISIBLE));
        row.addView(badge);
        return row;
    }

    private TextView label(String value) {
        TextView label = ui.createText("H4.Secondary", value);
        label.setPadding(0, dp(10), 0, dp(2));
        return label;
    }

    private ThemeSwatch swatch(String hex, String name) {
        ThemeSwatch swatch = new ThemeSwatch(activity, hex, name, ArsoundTheme.palette(activity, "primary"));
        swatch.setOnClickListener(v -> {
            writeField(hex);
            picker.setColor(Color.parseColor(hex));
        });
        return swatch;
    }

    /** Draws what depends on the colour: the disc, the badge, the note, the ring on a swatch, and tells the screen. */
    private void refresh() {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.parseColor(current));
        disc.setStroke(Math.max(1, dp(1)), 0x55808080);
        dot.setBackground(disc);

        String warning = ready ? listener.note(current) : null;
        note.setText(warning == null ? "" : warning);
        badge.setVisibility(warning == null ? View.INVISIBLE : View.VISIBLE);
        if (warning == null) note.setVisibility(View.GONE);
        else if (note.getVisibility() == View.GONE && !badgeHinted) {
            // The first time a colour is changed, the note shows by itself; afterwards the badge opens it.
            note.setVisibility(View.VISIBLE);
            badgeHinted = true;
        }

        String presetName = null;
        for (ColorPreset preset : presets) if (preset.hex.equalsIgnoreCase(current)) presetName = preset.name;
        presetsLabel.setText(presetName == null ? text("Готовые цвета", "Ready colours")
                : text("Готовые цвета · ", "Ready colours · ") + presetName);
        mark(recentGrid);
        mark(presetGrid);
        if (ready) listener.onLive(current);
    }

    private void mark(SwatchGrid grid) {
        if (grid == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            ThemeSwatch swatch = (ThemeSwatch) grid.getChildAt(i);
            swatch.setPicked(swatch.hex().equalsIgnoreCase(current));
        }
    }

    private void writeField(String hex) {
        writingField = true;
        String shown = field.getText().toString();
        if (!shown.equalsIgnoreCase(hex)) {
            field.setText(hex);
            field.setSelection(hex.length());
        }
        writingField = false;
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private void done() {
        // The sheet's own dismiss runs closed(), which keeps the colour.
        sheet.dismiss();
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        listener.onCancel();
        sheet.dismiss();
    }

    /** The sheet is gone: a colour that was not given up is kept, and remembered among the recent ones. */
    private void closed() {
        if (finished) return;
        finished = true;
        if (!current.equalsIgnoreCase(initial)) {
            List<String> recent = RecentColours.parse(Settings.getString(RecentColours.KEY, ""));
            Settings.putString(RecentColours.KEY, RecentColours.store(RecentColours.withAdded(recent, current)));
        }
        listener.onDone(current);
    }

    private int dp(float value) {
        return Dimens.dp(activity, value);
    }
}
