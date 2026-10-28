package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The bar pinned to the bottom of {@link CustomThemeScreen}: a line that says whether there are changes that are not
 * applied, the "Reset" button and the "Apply" button. "Apply" is dim while there is nothing to apply. After a reset the
 * "Reset" button becomes "Undo" for a few seconds, so a reset by a slip costs nothing.
 */
final class ThemeApplyBar extends LinearLayout {
    /** What the bar's buttons do. */
    interface Actions {
        void reset();

        void undoReset();

        void apply();
    }

    private static final long UNDO_MS = 5_000;

    private final Actions actions;
    private final TextView status;
    private final TextView resetButton;
    private final TextView applyButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable endUndo = this::endUndo;
    private boolean undoing;
    private boolean canApply = true;

    ThemeApplyBar(SettingsUi ui, Actions actions) {
        super(ui.activity);
        this.actions = actions;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(ui.dimen("spacing_m"), ui.dp(8), ui.dp(8), ui.dp(8));
        setBackgroundColor(ArsoundTheme.palette(ui.activity, "dialog"));
        setElevation(ui.dp(6));

        status = ui.createText("Body.Secondary", "");
        status.setMaxLines(2);
        addView(status, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        resetButton = (TextView) ui.createPillButton(text("Сбросить", "Reset"), false, v -> onResetTap());
        applyButton = (TextView) ui.createPillButton(text("Применить", "Apply"), true, v -> {
            if (canApply) actions.apply();
        });
        resetButton.setMinHeight(ui.dp(48));
        applyButton.setMinHeight(ui.dp(48));
        addView(resetButton);
        addView(applyButton);
    }

    /**
     * @param changed Whether the colours differ from the saved ones.
     * @param applied Whether the custom theme is the theme in use.
     */
    void show(boolean changed, boolean applied) {
        canApply = changed || !applied;
        applyButton.setAlpha(canApply ? 1f : .4f);
        status.setText(changed
                ? text("Есть несохранённые изменения. «Применить» перезапустит приложение.",
                "There are unsaved changes. Apply restarts the app.")
                : applied ? text("Тема применена.", "The theme is applied.")
                : text("Тема ещё не применена. «Применить» перезапустит приложение.",
                "The theme is not applied yet. Apply restarts the app."));
    }

    /** The reset was done: the button offers to take it back for a few seconds. */
    void offerUndo() {
        undoing = true;
        resetButton.setText(text("Отменить", "Undo"));
        handler.removeCallbacks(endUndo);
        handler.postDelayed(endUndo, UNDO_MS);
    }

    private void onResetTap() {
        if (undoing) {
            endUndo();
            actions.undoReset();
        } else {
            actions.reset();
        }
    }

    private void endUndo() {
        handler.removeCallbacks(endUndo);
        undoing = false;
        resetButton.setText(text("Сбросить", "Reset"));
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(endUndo);
        super.onDetachedFromWindow();
    }
}
