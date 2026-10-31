package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;
import app.revanced.extension.soundcloud.theme.PaletteEngine;
import app.revanced.extension.soundcloud.theme.RuntimeThemes;

/**
 * The editor of the custom theme. The drawing of the app ({@link ThemePreviewPair}) and the bar with "Reset" and "Apply"
 * ({@link ThemeApplyBar}) stay pinned on the screen; between them scroll the mode ({@link ThemeModeChips}), the three
 * colours ({@link ThemeColorTile}, each opens a {@link ColorPickerSheet} that changes the drawing live), the roundness
 * of the corners and the animation ({@link ThemeEffectStrip}).
 * <p>
 * {@link #fill} builds only the scrolling rows, which the settings search reads off screen as well; {@link #create}
 * adds the pinned parts around them.
 */
final class CustomThemeScreen extends SectionScreen {
    static final String ID = "custom_theme";

    CustomThemeScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Своя тема", "Custom theme");
    }

    private static final String STATE_DRAFT = "arsound_custom_theme_draft";

    /** The colours being edited; they are kept only when the user applies them. */
    private CustomTheme editing;
    /** What the colours were before the last reset, for "Undo". */
    private CustomTheme beforeReset;
    /** The colour whose picker is open, or null. */
    private ColorRole activeRole;
    private ThemePreviewPair previewPair;
    private ThemeApplyBar applyBar;
    private final List<ThemeColorTile> colourTiles = new ArrayList<>();
    /** Redraw the parts of the editor that depend on {@link #editing}. */
    private final List<java.util.function.Consumer<ArsoundTheme.Theme>> editorViews = new ArrayList<>();

    @Override
    void onSaveInstanceState(Bundle outState) {
        // Colours that were not applied yet would be gone when the system makes the screen again.
        if (editing != null) outState.putString(STATE_DRAFT, editing.toJson());
    }

    @Override
    boolean onBackPressed() {
        if (editing == null || !isChanged()) return false;
        new ArsoundDialog(activity)
                .setTitle(text("Выйти без применения?", "Leave without applying?"))
                .setMessage(text("Изменения цветов не сохранятся.", "The changes to the colours will be lost."))
                .setNegativeButton(text("Остаться", "Stay"), null)
                .setPositiveButton(text("Выйти", "Leave"), (dialog, which) -> activity.finish())
                .show();
        return true;
    }

    /** The screen: the toolbar with the title, the pinned drawing, the scrolling rows, the pinned bar. */
    @Override
    View create() {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        addTitleToToolbar((LinearLayout) root.getChildAt(0));
        fill(holder[0]);

        previewPair = new ThemePreviewPair(activity, ui);
        LinearLayout previewCard = new LinearLayout(activity);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setPadding(ui.dimen("spacing_m"), ui.dp(4), ui.dimen("spacing_m"), ui.dp(10));
        previewCard.addView(previewPair, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(previewCard, 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applyBar = new ThemeApplyBar(ui, new ThemeApplyBar.Actions() {
            @Override
            public void reset() {
                beforeReset = editing;
                editing = CustomTheme.defaults();
                refreshEditor();
                applyBar.offerUndo();
            }

            @Override
            public void undoReset() {
                if (beforeReset != null) editing = beforeReset;
                refreshEditor();
            }

            @Override
            public void apply() {
                editing.save();
                Toast.makeText(activity, text("Применяю тему…", "Applying the theme…"), Toast.LENGTH_SHORT).show();
                ArsoundTheme.setCurrent(activity, RuntimeThemes.CUSTOM);
                ArsoundTheme.restartApp(activity);
            }
        });
        root.addView(applyBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshNow();
        return root;
    }

    private void addTitleToToolbar(LinearLayout toolbar) {
        TextView heading = ui.createText("H3.Primary", title());
        heading.setSingleLine(true);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    @Override
    void fill(LinearLayout list) {
        CustomTheme draft = null;
        Bundle restoredState = activity.restoredState();
        if (restoredState != null) {
            String text = restoredState.getString(STATE_DRAFT);
            if (text != null) draft = CustomTheme.fromJson(text);
        }
        editing = draft != null ? draft : CustomTheme.load();
        editorViews.clear();
        colourTiles.clear();

        addModeRows(list);
        addColourRows(list);
        addShapeRows(list);
        addAnimationRows(list);

        View end = new View(activity);
        list.addView(end, new LinearLayout.LayoutParams(1, ui.dp(24)));
        // Read for the search, the rows need no theme worked out for them.
        if (!ui.search.isIndexing()) refreshNow();
    }

    // region Rows

    private void addModeRows(LinearLayout list) {
        ui.recordForSearch(text("Режим темы", "Theme mode"), text("Тёмная, светлая или как на телефоне", "Dark, light or like the phone"));
        list.addView(ui.createSubHeading(text("Режим", "Mode")));
        ThemeModeChips chips = new ThemeModeChips(ui, editing.mode, mode -> {
            editing = editing.withMode(mode);
            refreshEditor();
        });
        list.addView(chips, paddedParams(4));
        TextView note = ui.createText("Body.Secondary", "");
        note.setPadding(ui.dimen("spacing_m"), ui.dp(8), ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        list.addView(note);
        editorViews.add(theme -> {
            // "Reset" changes the mode without a tap on the chips.
            chips.setMode(editing.mode);
            note.setText(ThemeModeChips.explanation(editing.mode));
        });
    }

    private void addColourRows(LinearLayout list) {
        ui.recordForSearch(text("Акцент", "Accent"), "");
        ui.recordForSearch(text("Фон тёмного вида", "Dark background"), "");
        ui.recordForSearch(text("Фон светлого вида", "Light background"), "");
        list.addView(ui.createSubHeading(text("Цвета", "Colours")));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ui.dimen("spacing_m") - ui.dp(4), 0, ui.dimen("spacing_m") - ui.dp(4), 0);
        for (ColorRole role : ColorRole.values()) {
            ThemeColorTile tile = new ThemeColorTile(ui, role.title(), v -> pickColour(role));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = params.rightMargin = ui.dp(4);
            row.addView(tile, params);
            colourTiles.add(tile);
        }
        list.addView(row);
        editorViews.add(theme -> {
            for (ColorRole role : ColorRole.values()) {
                ThemeColorTile tile = colourTiles.get(role.ordinal());
                tile.show(role.get(editing), role == activeRole);
                tile.setVisibility(isShown(role) ? View.VISIBLE : View.GONE);
            }
        });

        TextView adjusted = ui.createText("Body.Secondary", "");
        adjusted.setPadding(ui.dimen("spacing_m"), ui.dp(10), ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        list.addView(adjusted);
        editorViews.add(theme -> {
            String note = adjustmentNote(theme);
            adjusted.setText(note);
            adjusted.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    /** The background of a look that the mode never shows is not offered. */
    private boolean isShown(ColorRole role) {
        if (role == ColorRole.DARK_BG) return !CustomTheme.MODE_LIGHT.equals(editing.mode);
        if (role == ColorRole.LIGHT_BG) return !CustomTheme.MODE_DARK.equals(editing.mode);
        return true;
    }

    private void addShapeRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Форма", "Shape")));
        IntConsumer[] setRadius = new IntConsumer[1];
        list.addView(ui.createSliderRow(text("Скругление углов", "Corner roundness"),
                text("Обложки, карточки и мини-плеер. Рисунок сверху показывает весь диапазон.",
                        "Covers, cards and the mini player. The drawing on top shows the whole range."),
                CustomTheme.MAX_RADIUS, editing.radius, value -> value + " dp", value -> {
                    editing = editing.withRadius(value);
                    refreshEditor();
                }, null, setRadius));
        editorViews.add(theme -> setRadius[0].accept(editing.radius));
    }

    private void addAnimationRows(LinearLayout list) {
        ui.recordForSearch(text("Анимация фона", "Background animation"), "");
        list.addView(ui.createSubHeading(text("Анимация фона", "Background animation")));
        // Six live animations are not made for the search, which reads only the words.
        if (ui.search.isIndexing()) return;
        ThemeEffectStrip strip = new ThemeEffectStrip(ui, effect -> {
            editing = editing.withEffect(effect);
            refreshEditor();
        });
        list.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        editorViews.add(theme -> strip.show(editing));
    }

    private LinearLayout.LayoutParams paddedParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = ui.dimen("spacing_m");
        params.topMargin = ui.dp(topDp);
        return params;
    }

    // endregion

    // region Editing

    private boolean isChanged() {
        return !editing.equals(CustomTheme.load());
    }

    /**
     * Draws the editor again after a change of {@link #editing}, once per frame: a drag of a slider or of the colour
     * picker changes it on every move of the finger, several times a frame.
     */
    private void refreshEditor() {
        if (refreshPosted) return;
        refreshPosted = true;
        Choreographer.getInstance().postFrameCallback(refreshFrame);
    }

    private boolean refreshPosted;
    private final Choreographer.FrameCallback refreshFrame = frameTimeNanos -> {
        refreshPosted = false;
        if (!activity.isFinishing()) refreshNow();
    };

    /** Draws the editor now. */
    private void refreshNow() {
        ArsoundTheme.Theme theme = RuntimeThemes.custom(activity, editing);
        if (previewPair != null) previewPair.show(theme, editing.mode);
        for (java.util.function.Consumer<ArsoundTheme.Theme> view : editorViews) view.accept(theme);
        if (applyBar != null) {
            boolean applied = CustomTheme.isSaved() && RuntimeThemes.CUSTOM.equals(ArsoundTheme.current());
            applyBar.show(isChanged(), applied);
        }
    }

    /** Opens the sheet of a colour; the drawing on top follows the finger, "Cancel" brings back the colour it had. */
    private void pickColour(ColorRole role) {
        String before = role.get(editing);
        boolean night = role.night(editing);
        activeRole = role;
        refreshEditor();
        ColorPickerSheet.show(ui, role.title(), before, role.presets(activity.getResources()), role.range(),
                new ColorPickerSheet.Listener() {
                    @Override
                    public void onLive(String hex) {
                        editing = role.with(editing, hex);
                        refreshEditor();
                    }

                    @Override
                    public void onDone(String hex) {
                        editing = role.with(editing, hex);
                        activeRole = null;
                        refreshEditor();
                    }

                    @Override
                    public void onCancel() {
                        editing = role.with(editing, before);
                        activeRole = null;
                        refreshEditor();
                    }

                    @Override
                    public String note(String hex) {
                        return role.note(hex, RuntimeThemes.custom(activity, role.with(editing, hex)), night);
                    }
                });
    }

    /** What the theme changed in the user's colours to keep the app readable, or an empty text. */
    private String adjustmentNote(ArsoundTheme.Theme theme) {
        int accent = Color.parseColor(editing.accent) & 0xFFFFFF;
        StringBuilder note = new StringBuilder();
        if (!CustomTheme.MODE_LIGHT.equals(editing.mode)) {
            if ((theme.color(true, "special") & 0xFFFFFF) != accent) {
                note.append(text("В тёмном виде акцент осветлён, чтобы читаться на фоне. ", "In the dark look the accent is lightened to read on the background. "));
            }
            if ((theme.color(true, "surface") & 0xFFFFFF) != (Color.parseColor(editing.darkBg) & 0xFFFFFF)) {
                note.append(text("Тёмный фон приведён к тёмному оттенку, чтобы на нём читались белые значки и текст. ",
                        "The dark background was brought to a dark shade so white icons and text read on it. "));
            } else if (PaletteEngine.darkTextAdjusted(editing.darkBg)) {
                note.append(text("В тёмном виде цвет текста слегка подправлен, чтобы читаться на карточках. ",
                        "In the dark look the text colour is slightly adjusted to read on the cards. "));
            }
        }
        if (!CustomTheme.MODE_DARK.equals(editing.mode)) {
            if ((theme.color(false, "special") & 0xFFFFFF) != accent) {
                note.append(text("В светлом виде акцент затемнён, чтобы читаться на светлом фоне. ", "In the light look the accent is darkened to read on the light background. "));
            }
            if ((theme.color(false, "surface") & 0xFFFFFF) != (Color.parseColor(editing.lightBg) & 0xFFFFFF)) {
                note.append(text("Светлый фон приведён к светлому оттенку. ", "The light background was brought to a light shade. "));
            }
        }
        return note.toString().trim();
    }

    // endregion
}
