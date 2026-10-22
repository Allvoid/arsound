package app.revanced.extension.soundcloud.player;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The sleep timer: music pauses after a set time ("in 30 minutes") or at a set clock time ("at 23:30").
 * Set from the "⋯" menu of the full screen player. The timer lives in the app process, like the music itself.
 */
@SuppressWarnings("unused")
public final class SleepTimer {
    private static final String ROW_TAG = "arsound_sleep_timer_row";
    private static final String PREFERENCES_NAME = "arsound_sleep_timer";
    /** SoundCloud's TrackMenuTypes.MENU_FOR_FULLSCREEN_PLAYER, as Params.toString() prints it. */
    private static final String PLAYER_MENU = "trackMenuType=1,";
    private static final int[] PRESETS = {15, 30, 60, 120};
    private static final int MINUTE_STEP = 5;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    /** When the music stops, in {@link System#currentTimeMillis()}; 0 when the timer is off. */
    private static long stopAt;

    private SleepTimer() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    private static final Runnable FIRE = () -> {
        long left = stopAt - System.currentTimeMillis();
        if (stopAt == 0) return;
        // The handler counts uptime, which stands still in deep sleep: check the clock and wait the rest.
        if (left > 1000) {
            handler.postDelayed(SleepTimer.FIRE, left);
            return;
        }
        stopAt = 0;
        pauseMusic();
    };

    public static boolean isActive() {
        return stopAt > System.currentTimeMillis();
    }

    private static void start(Context context, long at) {
        stopAt = at;
        handler.removeCallbacks(FIRE);
        handler.postDelayed(FIRE, Math.max(0, at - System.currentTimeMillis()));
        Logger.printInfo(() -> "Sleep timer: music stops at " + clock(at));
        Toast.makeText(context, text("Музыка остановится в ", "Music stops at ") + clock(at)
                + " (" + duration(minutesUntil(at)) + ")", Toast.LENGTH_SHORT).show();
    }

    private static void cancel(Context context) {
        stopAt = 0;
        handler.removeCallbacks(FIRE);
        Logger.printInfo(() -> "Sleep timer: off");
        Toast.makeText(context, text("Таймер сна выключен", "Sleep timer is off"), Toast.LENGTH_SHORT).show();
    }

    /** Pauses SoundCloud the way a headset button does. */
    private static void pauseMusic() {
        try {
            Context context = Utils.getContext();
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            Logger.printInfo(() -> "Sleep timer: music paused");
        } catch (Exception ex) {
            Logger.printException(() -> "Sleep timer could not pause the music", ex);
        }
    }

    // region Menu row

    /** The data consumer of the track menu being filled; it holds the menu fragment. Main thread only. */
    private static Object menuConsumer;

    /** Injection point. Called first when the track menu receives its data. */
    public static void setMenuConsumer(Object consumer) {
        menuConsumer = consumer;
    }

    /** Injection point. Called when the track menu receives its data, after {@link #setMenuConsumer}. */
    public static void onTrackMenu(Dialog dialog) {
        try {
            if (!isPlayerMenu(menuConsumer)) return;
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            Context context = dialog.getContext();
            String title = isActive()
                    ? text("Таймер сна: остановка в ", "Sleep timer: stops at ") + clock(stopAt)
                    : text("Таймер сна", "Sleep timer");
            ViewGroup row = DownloadTrackPatch.createMenuRow(context, title, "ic_actions_timer", v -> {
                dialog.dismiss();
                show(context);
            });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the sleep timer row", ex);
        }
    }

    /** True for the menu of the full screen player: its fragment arguments carry that menu type. */
    private static boolean isPlayerMenu(Object menuConsumer) throws Exception {
        if (menuConsumer == null) return false;
        for (Field field : menuConsumer.getClass().getDeclaredFields()) {
            if (!field.getType().getName().endsWith("TrackBottomSheetFragment")) continue;
            field.setAccessible(true);
            Object fragment = field.get(menuConsumer);
            if (fragment == null) return false;
            Bundle arguments = (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment);
            if (arguments == null) return false;
            arguments.setClassLoader(fragment.getClass().getClassLoader());
            Parcelable params = arguments.getParcelable("PARAMS_KEY");
            return params != null && params.toString().contains(PLAYER_MENU);
        }
        return false;
    }

    // endregion

    // region Dialog

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The timer window: "In time" or "At a time", the wheels to set it, and when the music stops. */
    public static void show(Context context) {
        new Window(context).show();
    }

    private static final class Window {
        final Context context;
        final int accent, primary, secondary;
        final SharedPreferences preferences;
        boolean atClock;

        final TextView inTimeTab, atClockTab;
        final NumberPicker hours, minutes;
        final TextView hoursUnit, minutesUnit, separator;
        final LinearLayout presets;
        final TextView summary;

        Window(Context context) {
            this.context = context;
            accent = ArsoundTheme.palette(context, "special");
            primary = ArsoundTheme.palette(context, "primary");
            secondary = ArsoundTheme.palette(context, "secondary");
            preferences = preferences(context);
            atClock = preferences.getBoolean("at_clock", false);

            inTimeTab = tab(text("Через время", "In time"));
            atClockTab = tab(text("Ко времени", "At a time"));
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

        void show() {
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
            inTimeTab.setOnClickListener(v -> setMode(false));
            atClockTab.setOnClickListener(v -> setMode(true));
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

            hours.setOnValueChangedListener((picker, old, value) -> refresh());
            minutes.setOnValueChangedListener((picker, old, value) -> refresh());
            applyMode();

            boolean active = isActive();
            ArsoundDialog dialog = new ArsoundDialog(context)
                    .setTitle(text("Таймер сна", "Sleep timer"))
                    .setView(root)
                    .setNegativeButton(text("Отмена", "Cancel"), null)
                    .setPositiveButton(active ? text("Изменить", "Update") : text("Запустить", "Start"), (d, which) -> {
                        remember();
                        SleepTimer.start(context, chosenStop());
                    });
            if (active) dialog.setNeutralButton(text("Выключить", "Turn off"), (d, which) -> cancel(context));
            dialog.show();
        }

        /** Hours and minutes on wheels, over a soft band that marks the chosen row. */
        View wheels() {
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

        void setMode(boolean clock) {
            if (atClock == clock) return;
            atClock = clock;
            applyMode();
        }

        /** Puts the wheels of the chosen mode on screen with the last values of that mode. */
        void applyMode() {
            styleTab(inTimeTab, !atClock);
            styleTab(atClockTab, atClock);
            // Hidden, not removed: the window keeps its height when the mode changes.
            presets.setVisibility(atClock ? View.INVISIBLE : View.VISIBLE);
            if (atClock) {
                int saved = preferences.getInt("clock", -1);
                if (saved < 0) {
                    // An hour from now, rounded up to five minutes.
                    Calendar later = Calendar.getInstance();
                    later.add(Calendar.MINUTE, 60 + MINUTE_STEP - later.get(Calendar.MINUTE) % MINUTE_STEP);
                    saved = later.get(Calendar.HOUR_OF_DAY) * 60 + later.get(Calendar.MINUTE);
                }
                setRange(hours, 0, 23, 1, true);
                setRange(minutes, 0, 59, 1, true);
                hours.setValue(saved / 60);
                minutes.setValue(saved % 60);
                hoursUnit.setVisibility(View.GONE);
                minutesUnit.setVisibility(View.GONE);
                separator.setVisibility(View.VISIBLE);
            } else {
                int saved = preferences.getInt("minutes", 30);
                setRange(hours, 0, 12, 1, false);
                setRange(minutes, 0, 60 - MINUTE_STEP, MINUTE_STEP, false);
                hours.setValue(saved / 60);
                minutes.setValue(saved % 60 / MINUTE_STEP);
                hoursUnit.setText(text("ч", "h"));
                minutesUnit.setText(text("мин", "min"));
                hoursUnit.setVisibility(View.VISIBLE);
                minutesUnit.setVisibility(View.VISIBLE);
                separator.setVisibility(View.GONE);
            }
            refresh();
        }

        /** A wheel from {@code min} to {@code max} by {@code step}; values are indexes, labels are the numbers. */
        void setRange(NumberPicker picker, int min, int max, int step, boolean twoDigits) {
            int count = (max - min) / step + 1;
            String[] labels = new String[count];
            for (int i = 0; i < count; i++) {
                int value = min + i * step;
                labels[i] = twoDigits || step > 1 ? String.format(Locale.ROOT, "%02d", value) : String.valueOf(value);
            }
            // The labels must be cleared before the range shrinks, or NumberPicker throws.
            picker.setDisplayedValues(null);
            picker.setMinValue(0);
            picker.setMaxValue(count - 1);
            picker.setDisplayedValues(labels);
            picker.setWrapSelectorWheel(true);
        }

        int chosenMinutes() {
            return hours.getValue() * 60 + minutes.getValue() * MINUTE_STEP;
        }

        long chosenStop() {
            if (!atClock) return System.currentTimeMillis() + chosenMinutes() * 60_000L;
            Calendar at = Calendar.getInstance();
            at.set(Calendar.HOUR_OF_DAY, hours.getValue());
            at.set(Calendar.MINUTE, minutes.getValue());
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            // A time that has passed today means tomorrow.
            if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_MONTH, 1);
            return at.getTimeInMillis();
        }

        void remember() {
            SharedPreferences.Editor editor = preferences.edit().putBoolean("at_clock", atClock);
            if (atClock) {
                editor.putInt("clock", hours.getValue() * 60 + minutes.getValue());
            } else {
                editor.putInt("minutes", chosenMinutes());
            }
            editor.apply();
        }

        /** The line under the wheels, the chosen preset and whether "Start" can be pressed. */
        void refresh() {
            boolean empty = !atClock && chosenMinutes() == 0;
            if (empty) {
                summary.setText(text("Выберите, через сколько остановить музыку", "Choose when to stop the music"));
                summary.setTextColor(secondary);
            } else {
                long at = chosenStop();
                summary.setText(atClock
                        ? text("Музыка остановится через ", "Music stops in ") + duration(minutesUntil(at))
                        : text("Музыка остановится в ", "Music stops at ") + clock(at));
                summary.setTextColor(accent);
            }
            for (int i = 0; i < presets.getChildCount(); i++) {
                TextView chip = (TextView) presets.getChildAt(i);
                styleChip(chip, !atClock && chosenMinutes() == PRESETS[i]);
            }
        }

        TextView tab(String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setGravity(Gravity.CENTER);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        void styleTab(TextView tab, boolean selected) {
            tab.setBackground(rounded(selected ? accent : Color.TRANSPARENT, 11));
            tab.setTextColor(selected ? contrast(accent) : withAlpha(primary, 0xB0));
        }

        NumberPicker picker() {
            NumberPicker picker = new NumberPicker(context);
            picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                picker.setTextColor(primary);
                picker.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30, context.getResources().getDisplayMetrics()));
                picker.setSelectionDividerHeight(0);
            }
            return picker;
        }

        TextView unit() {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.setTextColor(secondary);
            view.setPadding(dp(2), 0, dp(10), 0);
            return view;
        }

        TextView chip(int minutesValue) {
            TextView chip = new TextView(context);
            chip.setText(duration(minutesValue));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                hours.setValue(minutesValue / 60);
                minutes.setValue(minutesValue % 60 / MINUTE_STEP);
                refresh();
            });
            return chip;
        }

        void styleChip(TextView chip, boolean selected) {
            GradientDrawable shape = rounded(selected ? accent : Color.TRANSPARENT, 100);
            if (!selected) shape.setStroke(Math.max(1, dp(1)), withAlpha(primary, 0x40));
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x33)), shape, null));
            chip.setTextColor(selected ? contrast(accent) : primary);
        }

        GradientDrawable rounded(int color, float radius) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(radius));
            shape.setColor(color);
            return shape;
        }

        int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
        }
    }

    // endregion

    private static String clock(long at) {
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(at);
        return String.format(Locale.ROOT, "%d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE));
    }

    private static int minutesUntil(long at) {
        return (int) Math.max(1, Math.round((at - System.currentTimeMillis()) / 60_000.0));
    }

    /** "1 h 15 min", "45 min". */
    private static String duration(int minutes) {
        int hours = minutes / 60, rest = minutes % 60;
        if (hours == 0) return rest + text(" мин", " min");
        if (rest == 0) return hours + text(" ч", " h");
        return hours + text(" ч ", " h ") + rest + text(" мин", " min");
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int contrast(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }
}
