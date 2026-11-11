package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The small window of the versions of a track, opened by {@link VersionsButton}: the speed (normal, slowed, sped up), and
 * the reverb and the bass boost, each with a tick and a choice of weak, medium or strong. They combine; every change
 * is heard at once and remembered ({@link TrackVersions}).
 */
final class VersionsPopup {
    private final Context context;
    private final PopupParts parts;
    private final Runnable onChange;

    private VersionsPopup(Context context, Runnable onChange) {
        this.context = context;
        this.onChange = onChange;
        parts = new PopupParts(context);
    }

    /** Opens the window; {@code onChange} runs after every change, for the button to follow it. */
    static void show(Context context, Runnable onChange) {
        new VersionsPopup(context, onChange).show();
    }

    private void show() {
        LinearLayout root = new LinearLayout(context);
        root.setTag(ArsoundDialog.KEEP_COLORS);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(parts.dp(20), 0, parts.dp(20), 0);

        root.addView(parts.heading(text("Скорость и тон", "Speed and pitch")));
        root.addView(speedChips());

        root.addView(parts.heading(text("Эффекты", "Effects")));
        root.addView(effectRow("Reverb", TrackVersions.reverb(), TrackVersions::setReverb));
        root.addView(effectRow("Bass boost", TrackVersions.bass(), TrackVersions::setBass));
        new ArsoundDialog(context)
                .setTitle(text("Версии трека", "Versions of the track"))
                .setView(root)
                .setPositiveButton(text("Готово", "Done"), null)
                .show();
    }

    /** Normal, Slowed, Sped up: one of the three. */
    private View speedChips() {
        LinearLayout row = parts.chipRow();
        VersionSpeed chosen = TrackVersions.speed();
        TextView[] chips = new TextView[VersionSpeed.values().length];
        for (VersionSpeed speed : VersionSpeed.values()) {
            TextView chip = parts.chip(speed.label(), speed == chosen);
            chips[speed.ordinal()] = chip;
            chip.setOnClickListener(v -> {
                TrackVersions.setSpeed(speed);
                for (VersionSpeed other : VersionSpeed.values()) parts.paint(chips[other.ordinal()], other == speed);
                SlowedPlayback.onSettingChanged();
                onChange.run();
            });
            row.addView(chip, new LinearLayout.LayoutParams(0, parts.dp(40), 1));
        }
        return row;
    }

    /** A tick with the name of the effect; with it on, the choice of the strength shows under it. */
    private View effectRow(String name, VersionStrength current, Consumer<VersionStrength> store) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout strengths = parts.chipRow();
        VersionStrength[] last = {current.isOn() ? current : VersionStrength.MID};
        TextView[] chips = new TextView[VersionStrength.values().length];
        CheckBox box = new CheckBox(context);
        box.setText(name);
        box.setTextColor(parts.primary);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        box.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{parts.accent, parts.secondary}));
        box.setMinHeight(parts.dp(44));
        for (VersionStrength strength : VersionStrength.values()) {
            if (!strength.isOn()) continue;
            TextView chip = parts.chip(strength.label(), strength == last[0]);
            chips[strength.ordinal()] = chip;
            chip.setOnClickListener(v -> {
                last[0] = strength;
                store.accept(strength);
                for (VersionStrength other : VersionStrength.values()) {
                    if (other.isOn()) parts.paint(chips[other.ordinal()], other == strength);
                }
                AudioEqualizer.onVersionsChanged();
                onChange.run();
            });
            strengths.addView(chip, new LinearLayout.LayoutParams(0, parts.dp(36), 1));
        }
        box.setChecked(current.isOn());
        strengths.setVisibility(current.isOn() ? View.VISIBLE : View.GONE);
        box.setOnCheckedChangeListener((button, checked) -> {
            strengths.setVisibility(checked ? View.VISIBLE : View.GONE);
            store.accept(checked ? last[0] : VersionStrength.OFF);
            AudioEqualizer.onVersionsChanged();
            onChange.run();
        });
        column.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(strengths, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }
}
