package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import app.revanced.extension.soundcloud.player.AudioEqualizer;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/** A drop-down of presets over a curve of the bands that can be dragged; the user's own presets in the same list. */
final class EqualizerPanel {
    private final AudioEqualizer.Info info;
    private final LinearLayout options;
    private final int bands;
    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;
    private final int accent;
    private final int primary;
    private final int card;
    private short[] levels;
    private LinearLayout header, list;
    private ImageView chevron;
    private EqualizerGraph graph;
    /** What the header row says now, to tell a drag step (only the curve moves) from a new choice. */
    private String shownTitle, shownNote;

    EqualizerPanel(SettingsUi ui, AudioEqualizer.Info info, LinearLayout options) {
        this.ui = ui;
        this.activity = ui.activity;
        this.accent = ArsoundTheme.palette(activity, "special");
        this.primary = ui.themeColor("themeColorPrimary");
        this.card = ArsoundTheme.palette(activity, "highlight");
        this.info = info;
        this.options = options;
        this.bands = info.centerFrequenciesHz.length;
    }

    private EqualizerChoice choice() {
        return EqualizerChoice.parse(Settings.getString(AudioEqualizer.CHOICE, ""));
    }

    void build() {
        // A preset of the phone chosen in an older version becomes the user's own band levels.
        long legacy = Settings.getLong(AudioEqualizer.PRESET,
                AudioEqualizer.CUSTOM);
        if (legacy >= 0 && legacy < info.presetLevels.length) {
            AudioEqualizer.setLevels(info.presetLevels[(int) legacy], "");
        }
        levels = AudioEqualizer.levels(bands);

        // The card: the chosen preset on top, the list of presets unfolds under it.
        LinearLayout dropdown = ui.createColumn();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card);
        shape.setCornerRadius(ui.dp(18));
        shape.setStroke(ui.dp(1), (accent & 0x00FFFFFF) | 0x40000000);
        dropdown.setBackground(shape);
        dropdown.setClipToOutline(true);
        LinearLayout.LayoutParams dropdownParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dropdownParams.leftMargin = dropdownParams.rightMargin = ui.dimen("spacing_m");
        dropdownParams.topMargin = ui.dimen("spacing_s");
        dropdownParams.bottomMargin = ui.dimen("spacing_m");
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        dropdown.addView(header);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setVisibility(View.GONE);
        dropdown.addView(list);
        chevron = new ImageView(activity);
        chevron.setImageDrawable(ui.lineIcon(new float[]{.22f, .4f, .5f, .64f, .78f, .4f}, primary));
        header.setOnClickListener(v -> {
            boolean open = list.getVisibility() != View.VISIBLE;
            if (open) fillList();
            ui.foldOptions(list, chevron, open);
        });
        options.addView(dropdown, dropdownParams);

        graph = new EqualizerGraph(activity, info.centerFrequenciesHz, info.minLevel, info.maxLevel,
                accent, primary, card);
        graph.setLevels(levels, false);
        graph.setListener((band, level) -> {
            levels[band] = level;
            AudioEqualizer.setLevel(band, level, bands);
            showHeader();
        });
        LinearLayout.LayoutParams graphParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        graphParams.leftMargin = graphParams.rightMargin = ui.dimen("spacing_m");
        options.addView(graph, graphParams);
        TextView hint = ui.createText("Body.Secondary", text(
                "Тяните точки вверх и вниз, чтобы подстроить звук. Свою настройку можно сохранить под своим именем — "
                        + "она появится в списке пресетов; удерживайте её там, чтобы переименовать или удалить.",
                "Drag the points up and down to tune the sound. Your tuning can be saved under a name and shows up in "
                        + "the preset list; hold it there to rename or delete it."));
        hint.setPadding(ui.dimen("spacing_m"), ui.dp(10), ui.dimen("spacing_m"), ui.dp(10));
        options.addView(hint);

        TextView save = ui.createText("H4.Primary", text("+ Сохранить как свой пресет", "+ Save as my preset"));
        save.setTextColor(accent);
        save.setGravity(Gravity.CENTER);
        save.setMinHeight(ui.dp(44));
        save.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));
        GradientDrawable dashed = new GradientDrawable();
        dashed.setCornerRadius(ui.dp(100));
        dashed.setStroke(Math.max(1, ui.dp(1)), accent, ui.dp(5), ui.dp(4));
        save.setBackground(new RippleDrawable(
                ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x40000000), dashed, null));
        save.setOnClickListener(v -> askName(null));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.leftMargin = ui.dimen("spacing_m");
        saveParams.bottomMargin = ui.dimen("spacing_m");
        options.addView(save, saveParams);
        showHeader();
    }

    /** Name, note and curve of what is chosen now, in the closed drop-down. */
    private void showHeader() {
        EqualizerChoice choice = choice();
        String title, note;
        short[] shape = levels;
        AudioEqualizer.Preset preset = choice.isBuiltIn() ? AudioEqualizer.preset(choice.value()) : null;
        if (preset != null) {
            title = preset.name();
            note = preset.note();
        } else if (choice.isUser()) {
            title = choice.value();
            note = text("Ваш пресет", "Your preset");
        } else {
            title = text("Своя настройка", "Custom");
            note = text("Не сохранена", "Not saved");
        }
        // Every step of a drag lands here: while the words stay the same, only the small curve is redrawn.
        View thumbnail = header.findViewWithTag("curve");
        if (thumbnail != null && title.equals(shownTitle) && note.equals(shownNote)) {
            thumbnail.setBackground(curveThumbnail(shape));
            return;
        }
        shownTitle = title;
        shownNote = note;
        TextView old = header.getChildCount() > 0 ? (TextView) header.findViewWithTag("name") : null;
        boolean changed = old == null || !title.equals(old.getText().toString());
        header.removeAllViews();
        View row = presetRow(title, note, shape, false);
        // The row's views move into the header, which keeps its own click and the arrow.
        ViewGroup source = (ViewGroup) row;
        while (source.getChildCount() > 0) {
            View child = source.getChildAt(0);
            source.removeViewAt(0);
            header.addView(child);
        }
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());
        header.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackground));
        if (chevron.getParent() != null) ((ViewGroup) chevron.getParent()).removeView(chevron);
        header.addView(chevron, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        header.setContentDescription(text("Пресет: ", "Preset: ") + title);
        if (changed) {
            View name = header.findViewWithTag("name");
            name.setAlpha(0f);
            name.setTranslationY(ui.dp(6));
            name.animate().alpha(1f).translationY(0).setDuration(220).start();
        }
    }

    /** The unfolding list: built-in presets, then the user's. */
    private void fillList() {
        list.removeAllViews();
        EqualizerChoice choice = choice();
        View divider = new View(activity);
        divider.setBackgroundColor((primary & 0x00FFFFFF) | 0x1A000000);
        list.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1) / 2)));
        for (AudioEqualizer.Preset preset : AudioEqualizer.PRESETS) {
            short[] shape = preset.levels(info);
            EqualizerChoice builtIn = EqualizerChoice.builtIn(preset.id);
            View row = presetRow(preset.name(), preset.note(), shape, builtIn.equals(choice));
            row.setOnClickListener(v -> choose(shape, builtIn));
            list.addView(row);
        }
        List<AudioEqualizer.UserPreset> mine = AudioEqualizer.userPresets(bands);
        if (mine.isEmpty()) return;
        TextView heading = ui.createText("H4.Secondary", text("Мои пресеты", "My presets"));
        heading.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(4));
        list.addView(heading);
        for (AudioEqualizer.UserPreset preset : mine) {
            EqualizerChoice own = EqualizerChoice.user(preset.name);
            View row = presetRow(preset.name, text("Ваш пресет · удерживайте, чтобы изменить", "Your preset · hold to edit"),
                    preset.levels, own.equals(choice));
            row.setOnClickListener(v -> choose(preset.levels, own));
            row.setOnLongClickListener(v -> {
                editUserPreset(preset.name);
                return true;
            });
            list.addView(row);
        }
    }

    /** A narrower scale than the big curve (±10 dB), so gentle presets still show their shape. */
    private CurveThumbnail curveThumbnail(short[] shape) {
        return new CurveThumbnail(shape, (short) -1000, (short) 1000, accent,
                (primary & 0x00FFFFFF) | 0x14000000, ui.dp(1));
    }

    /** A preset row: a small picture of its curve, the name and the note, a tick when chosen. */
    private View presetRow(String title, String note, short[] shape, boolean selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackground));
        row.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));

        View curve = new View(activity);
        curve.setTag("curve");
        curve.setBackground(curveThumbnail(shape));
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(36));
        curveParams.rightMargin = ui.dp(14);
        row.addView(curve, curveParams);

        LinearLayout texts = ui.createColumn();
        TextView name = ui.createText("H4.Primary", title);
        name.setTag("name");
        if (selected) name.setTextColor(accent);
        texts.addView(name);
        texts.addView(ui.createText("Body.Secondary", note));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            ImageView tick = new ImageView(activity);
            tick.setImageDrawable(ui.lineIcon(new float[]{.2f, .52f, .42f, .72f, .82f, .3f}, accent));
            row.addView(tick, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        }
        return row;
    }

    private void choose(short[] chosen, EqualizerChoice choice) {
        levels = chosen.clone();
        AudioEqualizer.setLevels(levels, choice.toStored());
        ui.foldOptions(list, chevron, false);
        graph.setLevels(levels, true);
        showHeader();
    }

    /** After saving, renaming or deleting: the header, and the list if it is open. */
    private void refresh() {
        showHeader();
        if (list.getVisibility() == View.VISIBLE) fillList();
    }

    /** Asks for a name: to save the current curve, or to rename the given preset. */
    private void askName(String renaming) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(renaming != null ? renaming
                : text("Мой пресет ", "My preset ") + (AudioEqualizer.userPresets(bands).size() + 1));
        input.selectAll();
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(ui.dimen("spacing_m"), ui.dp(8), ui.dimen("spacing_m"), 0);
        box.addView(input);
        new ArsoundDialog(activity)
                .setTitle(renaming != null ? text("Новое имя", "New name") : text("Сохранить пресет", "Save preset"))
                .setView(box)
                .setPositiveButton(text("Готово", "Done"), (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (renaming != null) {
                        AudioEqualizer.renameUserPreset(renaming, value, bands);
                    } else {
                        AudioEqualizer.saveUserPreset(value, levels);
                    }
                    refresh();
                })
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
        input.requestFocus();
    }

    private void editUserPreset(String presetName) {
        new ArsoundDialog(activity)
                .setTitle(presetName)
                .setItems(new String[]{text("Переименовать", "Rename"), text("Удалить", "Delete")}, (dialog, which) -> {
                    if (which == 0) {
                        askName(presetName);
                    } else {
                        AudioEqualizer.deleteUserPreset(presetName, bands);
                        refresh();
                    }
                })
                .show();
    }
}
