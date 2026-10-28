package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.app.Dialog;
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
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;

/**
 * The dialog that picks one colour of the custom theme: a small drawing of the app in the theme with the candidate in
 * place, which follows the finger, the {@link HsvColorPicker}, a field for the hex code and a row of ready colours.
 */
public final class ColorPickerDialog {
    /** What the dialog draws for a candidate colour. */
    public interface Preview {
        /** The theme with this colour in place. */
        ArsoundTheme.Theme theme(String hex);

        /** Whether the look drawn is the dark one. */
        boolean night();

        /** A warning when the theme changed this colour to keep the app readable, else null. */
        String note(String hex, ArsoundTheme.Theme theme);
    }

    private final Activity activity;
    private final Preview preview;
    private final String[] presets;
    private final ThemePreviewView previewView;
    private final HsvColorPicker picker;
    private final EditText field;
    private final TextView note;
    private final LinearLayout swatches;
    private String current;
    private boolean writingField;

    private ColorPickerDialog(Activity activity, String initial, String[] presets, Preview preview) {
        this.activity = activity;
        this.preview = preview;
        this.presets = presets;
        this.current = initial;
        previewView = new ThemePreviewView(activity);
        picker = new HsvColorPicker(activity);
        picker.setContentDescription(text("Выбор цвета", "Colour picker"));
        field = new EditText(activity);
        note = styled("Body.Secondary", "");
        swatches = new LinearLayout(activity);
    }

    /**
     * @param presets Ready colours, #RRGGBB.
     * @param picked  Told the chosen colour, #RRGGBB, when the user confirms.
     */
    public static void show(Activity activity, String title, String initial, String[] presets, Preview preview,
                            Consumer<String> picked) {
        ColorPickerDialog dialog = new ColorPickerDialog(activity, CustomTheme.hex(initial, "#FF5500"), presets, preview);
        View content = dialog.build();
        Dialog shown = new ArsoundDialog(activity)
                .setTitle(title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Выбрать", "Select"), (d, which) -> picked.accept(dialog.current))
                .show();
        if (shown.getWindow() != null) {
            shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private View build() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), 0);

        // The drawing on the left; the code and the warning beside it.
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(previewView, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView label = styled("Body.Secondary", "HEX");
        label.setTag(ArsoundDialog.KEEP_COLORS);
        label.setTextColor(ArsoundTheme.palette(activity, "secondary"));
        side.addView(label);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7), (source, start, end, dest, ds, de) -> {
            StringBuilder kept = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c == '#' || CustomTheme.isHexDigit(c)) kept.append(Character.toUpperCase(c));
            }
            return kept.length() == end - start ? null : kept.toString();
        }});
        int fieldId = Utils.getResourceIdentifier(ResourceType.STYLE, "H4.Primary");
        if (fieldId != 0) field.setTextAppearance(fieldId);
        side.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        note.setTag(ArsoundDialog.KEEP_COLORS);
        note.setTextColor(ArsoundTheme.palette(activity, "secondary"));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(6), 0, 0);
        // Room for the warning at all times, so the picker below does not jump when it appears.
        note.setMinLines(3);
        side.addView(note);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sideParams.leftMargin = dp(16);
        top.addView(side, sideParams);
        content.addView(top);

        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickerParams.topMargin = dp(10);
        content.addView(picker, pickerParams);

        if (presets != null && presets.length > 0) {
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            swatches.setOrientation(LinearLayout.HORIZONTAL);
            swatches.setPadding(0, dp(4), 0, dp(4));
            for (String preset : presets) swatches.addView(swatch(preset));
            scroll.addView(swatches);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollParams.topMargin = dp(4);
            content.addView(scroll, scrollParams);
        }

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
                String text = s.toString().replace("#", "");
                String valid = CustomTheme.hex(text, null);
                if (text.length() == 6 && valid != null) picker.setColor(Color.parseColor(valid));
            }
        });
        writeField(current);
        picker.setColor(Color.parseColor(current));
        return content;
    }

    /** A round swatch of a ready colour; a ring marks the one that is picked. */
    private View swatch(String color) {
        View view = new View(activity);
        view.setTag(color);
        view.setContentDescription(color);
        view.setOnClickListener(v -> {
            current = color;
            writeField(color);
            picker.setColor(Color.parseColor(color));
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        params.rightMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private void refresh() {
        ArsoundTheme.Theme theme = preview.theme(current);
        previewView.setTheme(theme, preview.night());
        String warning = preview.note(current, theme);
        note.setText(warning == null ? "" : warning);
        int ring = ArsoundTheme.palette(activity, "primary");
        for (int i = 0; i < swatches.getChildCount(); i++) {
            View swatch = swatches.getChildAt(i);
            String color = (String) swatch.getTag();
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.parseColor(color));
            boolean picked = color.equalsIgnoreCase(current);
            shape.setStroke(dp(picked ? 3 : 1), picked ? ring : 0x33808080);
            swatch.setBackground(shape);
        }
    }

    private void writeField(String hex) {
        writingField = true;
        String text = field.getText().toString();
        if (!text.equalsIgnoreCase(hex)) {
            field.setText(hex);
            field.setSelection(hex.length());
        }
        writingField = false;
    }

    private static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private TextView styled(String style, String value) {
        TextView view = new TextView(activity);
        int id = Utils.getResourceIdentifier(ResourceType.STYLE, style);
        if (id != 0) view.setTextAppearance(id);
        view.setText(value);
        return view;
    }

    private int dp(float value) {
        return Dimens.dp(activity, value);
    }
}
