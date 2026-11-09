package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Colors.contrast;
import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** The timer window: "In time" or "At a time", the wheels to set it, and when the music stops. */
final class SleepTimerDialog {
    private static final String PREFERENCES_NAME = "arsound_sleep_timer";
    private static final String AT_CLOCK_KEY = "at_clock";
    private static final int[] PRESETS = {15, 30, 60, 120};

    private final Context context;
    private final int accent, primary, secondary;
    private final SharedPreferences preferences;
    private TimerMode mode;
    /** The window on screen; null until it is shown. */
    private Dialog shown;

    private final TextView inTimeTab, atClockTab;
    private final NumberPicker hours, minutes;
    private final TextView hoursUnit, minutesUnit, separator;
    private final LinearLayout presets;
    private final TextView summary;

    private SleepTimerDialog(Context context) {
        this.context = context;
        accent = ArsoundTheme.palette(context, "special");
        primary = ArsoundTheme.palette(context, "primary");
        secondary = ArsoundTheme.palette(context, "secondary");
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        mode = TimerMode.of(preferences.getBoolean(AT_CLOCK_KEY, false));

        inTimeTab = tab(TimerMode.of(false).tabLabel());
        atClockTab = tab(TimerMode.of(true).tabLabel());
        hours = picker();
        minutes = picker();
        hoursUnit = unit();
        minutesUnit = unit();
        separator = new TextView(context);
        separator.setText(":");
        separator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        separator.setTypeface(Typeface.DEFAULT_BOLD);
        separator.setTextColor(primary);
        presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        summary = new TextView(context);
        summary.setGravity(Gravity.CENTER);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        summary.setTextColor(secondary);
    }

    /** Opens the window. */
    static void show(Context context) {
        new SleepTimerDialog(context).show();
    }

    private void show() {
        LinearLayout root = new LinearLayout(context);
        root.setTag(ArsoundDialog.KEEP_COLORS);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), 0, dp(20), 0);

        // The two modes, like the SoundCloud / Arsound switch of the search.
        LinearLayout toggle = new LinearLayout(context);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setPadding(dp(3), dp(3), dp(3), dp(3));
        toggle.setBackground(rounded(withAlpha(primary, 0x14), 14));
        toggle.addView(inTimeTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        toggle.addView(atClockTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        inTimeTab.setOnClickListener(v -> setMode(TimerMode.of(false)));
        atClockTab.setOnClickListener(v -> setMode(TimerMode.of(true)));
        root.addView(toggle);

        root.addView(wheels(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int preset : PRESETS) presets.addView(chip(preset));
        LinearLayout.LayoutParams presetsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetsParams.topMargin = dp(4);
        root.addView(presets, presetsParams);

        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(14);
        summaryParams.bottomMargin = dp(4);
        root.addView(summary, summaryParams);

        // One tick, small and in the summary's colour: the window keeps its look and nearly its height.
        CheckBox fade = new CheckBox(context);
        fade.setText(text("Плавно затихать в последние 45 секунд", "Fade out over the last 45 seconds"));
        fade.setTextColor(secondary);
        fade.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fade.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent, secondary}));
        fade.setChecked(SleepTimerScheduler.fadeEnabled(context));
        fade.setOnCheckedChangeListener((button, checked) -> SleepTimerScheduler.setFadeEnabled(context, checked));
        LinearLayout.LayoutParams fadeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fadeParams.gravity = Gravity.CENTER_HORIZONTAL;
        fadeParams.bottomMargin = dp(2);
        root.addView(fade, fadeParams);

        hours.setOnValueChangedListener((picker, old, value) -> refresh());
        minutes.setOnValueChangedListener((picker, old, value) -> refresh());
        applyMode();

        boolean active = SleepTimerScheduler.isActive();
        ArsoundDialog dialog = new ArsoundDialog(context)
                .setTitle(text("Таймер сна", "Sleep timer"))
                .setView(root)
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .setPositiveButton(active ? text("Изменить", "Update") : text("Запустить", "Start"), (d, which) -> {
                    remember();
                    SleepTimerScheduler.start(context, mode.stopAt(chosenValue(), System.currentTimeMillis()));
                });
        if (active) {
            dialog.setNeutralButton(text("Выключить", "Turn off"), (d, which) -> SleepTimerScheduler.cancel(context));
        }
        shown = dialog.show();
        // The buttons exist only now: "Start" is off while no time is chosen.
        refresh();
    }

    /** Hours and minutes on wheels, over a soft band that marks the chosen row. */
    private View wheels() {
        FrameLayout frame = new FrameLayout(context);
        View band = new View(context);
        band.setBackground(rounded(withAlpha(accent, 0x22), 14));
        FrameLayout.LayoutParams bandParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bandParams.gravity = Gravity.CENTER_VERTICAL;
        bandParams.leftMargin = dp(28);
        bandParams.rightMargin = dp(28);
        frame.addView(band, bandParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(hours, new LinearLayout.LayoutParams(dp(72), dp(170)));
        row.addView(hoursUnit);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        separatorParams.leftMargin = dp(6);
        separatorParams.rightMargin = dp(6);
        row.addView(separator, separatorParams);
        row.addView(minutes, new LinearLayout.LayoutParams(dp(72), dp(170)));
        row.addView(minutesUnit);
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        rowParams.bottomMargin = dp(8);
        frame.addView(row, rowParams);
        return frame;
    }

    private void setMode(TimerMode next) {
        if (mode.isAtClock() == next.isAtClock()) return;
        mode = next;
        applyMode();
    }

    /** Puts the wheels of the chosen mode on screen with the last values of that mode. */
    private void applyMode() {
        styleTab(inTimeTab, !mode.isAtClock());
        styleTab(atClockTab, mode.isAtClock());
        // Hidden, not removed: the window keeps its height when the mode changes.
        presets.setVisibility(mode.hasPresets() ? View.VISIBLE : View.INVISIBLE);
        int saved = mode.initialValue(preferences.getInt(mode.preferenceKey(), mode.missingValue()),
                System.currentTimeMillis());
        setRange(hours, mode.hoursWheel());
        setRange(minutes, mode.minutesWheel());
        hours.setValue(mode.hoursIndex(saved));
        minutes.setValue(mode.minutesIndex(saved));
        if (mode.labelsWheelsWithUnits()) {
            hoursUnit.setText(text("ч", "h"));
            minutesUnit.setText(text("мин", "min"));
            hoursUnit.setVisibility(View.VISIBLE);
            minutesUnit.setVisibility(View.VISIBLE);
            separator.setVisibility(View.GONE);
        } else {
            hoursUnit.setVisibility(View.GONE);
            minutesUnit.setVisibility(View.GONE);
            separator.setVisibility(View.VISIBLE);
        }
        refresh();
    }

    /** Puts a wheel on the positions of the given range. */
    private void setRange(NumberPicker picker, TimerWheel wheel) {
        String[] labels = wheel.labels();
        // The labels must be cleared before the range shrinks, or NumberPicker throws.
        picker.setDisplayedValues(null);
        picker.setMinValue(0);
        picker.setMaxValue(labels.length - 1);
        picker.setDisplayedValues(labels);
        picker.setWrapSelectorWheel(true);
    }

    /** The value the wheels show now, in the units of the mode. */
    private int chosenValue() {
        return mode.value(hours.getValue(), minutes.getValue());
    }

    private void remember() {
        preferences.edit().putBoolean(AT_CLOCK_KEY, mode.isAtClock())
                .putInt(mode.preferenceKey(), chosenValue()).apply();
    }

    /** The line under the wheels, the chosen preset and whether "Start" can be pressed. */
    private void refresh() {
        int value = chosenValue();
        boolean empty = mode.isEmpty(value);
        if (shown != null) {
            TextView ok = ArsoundDialog.getButton(shown, DialogInterface.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setEnabled(!empty);
                ok.setAlpha(empty ? 0.4f : 1f);
            }
        }
        summary.setText(mode.summary(value, System.currentTimeMillis()));
        summary.setTextColor(empty ? secondary : accent);
        for (int i = 0; i < presets.getChildCount(); i++) {
            TextView chip = (TextView) presets.getChildAt(i);
            styleChip(chip, mode.isPreset(value, PRESETS[i]));
        }
    }

    private TextView tab(String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackground(rounded(selected ? accent : Color.TRANSPARENT, 11));
        tab.setTextColor(selected ? contrast(accent) : withAlpha(primary, 0xB0));
    }

    private NumberPicker picker() {
        NumberPicker picker = new NumberPicker(context);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            picker.setTextColor(primary);
            picker.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30, context.getResources().getDisplayMetrics()));
            picker.setSelectionDividerHeight(0);
        }
        return picker;
    }

    private TextView unit() {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(secondary);
        view.setPadding(dp(2), 0, dp(10), 0);
        return view;
    }

    private TextView chip(int minutesValue) {
        TextView chip = new TextView(context);
        chip.setText(TimerFormat.duration(minutesValue));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> {
            hours.setValue(mode.hoursIndex(minutesValue));
            minutes.setValue(mode.minutesIndex(minutesValue));
            refresh();
        });
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable shape = rounded(selected ? accent : Color.TRANSPARENT, 100);
        if (!selected) shape.setStroke(Math.max(1, dp(1)), withAlpha(primary, 0x40));
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x33)), shape, null));
        chip.setTextColor(selected ? contrast(accent) : primary);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private int dp(float value) {
        return Dimens.dp(context, value);
    }
}
