package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.Wallhaven;
import app.revanced.extension.soundcloud.wallpaper.WallhavenRules;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The filters of the Wallhaven browser in a bottom sheet: the categories and the shape of the pictures as chips, then,
 * for those who use them, the API key and the 18+ mode, and at the bottom a way to remove the current wallpaper. The
 * choice applies with the "Show" button; the search starts over only if something changed.
 */
final class WallhavenFilterSheet {
    private final SettingsUi ui;
    private final Wallhaven.Query query;
    private final Runnable onChanged;
    /** Reset was tapped: the sorting goes back to the app's choice too. */
    private boolean automaticSorting;
    private int categories;
    private boolean portrait;
    private int adultPurity;
    private View adultChips;
    private ToggleRow adultSwitch;
    private ThemeSheet sheet;

    private WallhavenFilterSheet(SettingsUi ui, Wallhaven.Query query, Runnable onChanged) {
        this.ui = ui;
        this.query = query;
        this.onChanged = onChanged;
        categories = query.categories;
        portrait = query.portrait;
        adultPurity = query.adultPurity;
    }

    /**
     * @param query     The search being edited; its filters are changed in place when the user applies them.
     * @param onChanged Called after the filters changed, to search again.
     * @param onClosed  Called when the sheet is gone, whatever was changed in it (the key and the 18+ mode count as filters).
     */
    static void show(SettingsUi ui, Wallhaven.Query query, Runnable onChanged, Runnable onClosed) {
        WallhavenFilterSheet filters = new WallhavenFilterSheet(ui, query, onChanged);
        filters.sheet = new ThemeSheet(ui.activity)
                .setTitle(text("Фильтры", "Filters"))
                .setContent(filters.build())
                .setDim(.35f)
                .setMaxHeight(.8f)
                .setOnDismiss(onClosed);
        filters.sheet.show();
    }

    private View build() {
        LinearLayout content = ui.createColumn();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8));

        content.addView(heading(text("Категории", "Categories")));
        String[] names = {text("Общие", "General"), text("Аниме", "Anime"), text("Люди", "People")};
        int[] bits = {Wallhaven.GENERAL, Wallhaven.ANIME, Wallhaven.PEOPLE};
        LinearLayout categoryRow = chipRow();
        for (int i = 0; i < bits.length; i++) {
            int bit = bits[i];
            TextView[] chip = new TextView[1];
            chip[0] = WallpaperChip.create(ui, names[i], (categories & bit) != 0, v -> {
                int next = (categories & bit) != 0 ? categories & ~bit : categories | bit;
                // At least one category stays on: the service answers nothing otherwise.
                if (next == 0) return;
                categories = next;
                WallpaperChip.style(ui, chip[0], (categories & bit) != 0);
            });
            addChip(categoryRow, chip[0], i);
        }
        content.addView(categoryRow);

        content.addView(heading(text("Форма картинок", "Shape of the pictures")));
        LinearLayout shapeRow = chipRow();
        TextView tall = WallpaperChip.create(ui, text("Для телефона", "For a phone"), portrait, null);
        TextView wide = WallpaperChip.create(ui, text("Горизонтальные", "Wide"), !portrait, null);
        tall.setOnClickListener(v -> {
            portrait = true;
            WallpaperChip.style(ui, tall, true);
            WallpaperChip.style(ui, wide, false);
        });
        wide.setOnClickListener(v -> {
            portrait = false;
            WallpaperChip.style(ui, tall, false);
            WallpaperChip.style(ui, wide, true);
        });
        addChip(shapeRow, tall, 0);
        addChip(shapeRow, wide, 1);
        content.addView(shapeRow);
        String tallSize = WallhavenRules.size(WallhavenRules.TALL_MIN_WIDTH, WallhavenRules.TALL_MIN_HEIGHT);
        String bigWideSize = WallhavenRules.size(WallhavenRules.WIDE_FOR_PHONE_MIN_WIDTH, WallhavenRules.WIDE_FOR_PHONE_MIN_HEIGHT);
        String wideSize = WallhavenRules.size(WallhavenRules.WIDE_MIN_WIDTH, WallhavenRules.WIDE_MIN_HEIGHT);
        TextView shapeNote = ui.createText("Body.Secondary", text(
                "Для телефона: сначала вертикальные, от " + tallSize + ". Когда они кончатся, дальше идут широкие от "
                        + bigWideSize + " с пометкой: берётся их середина. Горизонтальные: только широкие, от " + wideSize
                        + ", тоже середина.",
                "For a phone: tall pictures first, at least " + tallSize + ". When they run out, wide ones from "
                        + bigWideSize + " follow, marked: the middle of each is used. Wide: only wide pictures, at least "
                        + wideSize + ", the middle of them too."));
        shapeNote.setPadding(0, ui.dp(6), 0, 0);
        content.addView(shapeNote);

        adultChips = createAdultChips();
        content.addView(adultChips);

        content.addView(heading(text("Доступ к 18+", "Access to 18+")));
        LinearLayout access = ui.createColumn();
        // The rows bring their own side padding; the sheet's padding is taken away here.
        LinearLayout.LayoutParams accessParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        accessParams.leftMargin = accessParams.rightMargin = -ui.dp(20) + ui.dp(4);
        ActionRow[] keyRow = new ActionRow[1];
        keyRow[0] = WallhavenKeyDialog.createRow(ui, () -> {
            keyRow[0].description().setText(WallhavenKeyDialog.summary());
            updateAdult();
        });
        access.addView(keyRow[0]);
        adultSwitch = WallhavenKeyDialog.createAdultRow(ui, this::updateAdult);
        access.addView(adultSwitch);
        content.addView(access, accessParams);

        if (WallpaperStore.has()) {
            TextView remove = ui.createText("H4.Primary", text("Убрать текущие обои", "Remove the current wallpaper"));
            remove.setTextColor(ArsoundTheme.palette(ui.activity, "error"));
            remove.setMinHeight(ui.dp(48));
            remove.setGravity(Gravity.CENTER_VERTICAL);
            remove.setOnClickListener(v -> confirmRemove());
            content.addView(remove);
        }

        LinearLayout buttons = new LinearLayout(ui.activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, ui.dp(10), 0, ui.dp(4));
        buttons.addView(button(text("Сбросить", "Reset"), false, v -> {
            categories = Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE;
            portrait = true;
            adultPurity = Wallhaven.SKETCHY | Wallhaven.NSFW;
            automaticSorting = true;
            apply();
        }));
        buttons.addView(button(text("Показать", "Show"), true, v -> apply()));
        content.addView(buttons);

        updateAdult();
        return content;
    }

    /** Sketchy and NSFW chips: only while the 18+ mode is allowed. */
    private View createAdultChips() {
        LinearLayout box = ui.createColumn();
        box.addView(heading("18+"));
        LinearLayout row = chipRow();
        String[] names = {"Sketchy", "NSFW"};
        int[] bits = {Wallhaven.SKETCHY, Wallhaven.NSFW};
        for (int i = 0; i < bits.length; i++) {
            int bit = bits[i];
            TextView[] chip = new TextView[1];
            chip[0] = WallpaperChip.create(ui, names[i], (adultPurity & bit) != 0, v -> {
                adultPurity = (adultPurity & bit) != 0 ? adultPurity & ~bit : adultPurity | bit;
                WallpaperChip.style(ui, chip[0], (adultPurity & bit) != 0);
            });
            addChip(row, chip[0], i);
        }
        box.addView(row);
        return box;
    }

    /** The 18+ chips and the switch follow the key and the switch. */
    private void updateAdult() {
        adultChips.setVisibility(Wallhaven.adultAllowed() ? View.VISIBLE : View.GONE);
        adultSwitch.setVisibility(Wallhaven.key().isEmpty() ? View.GONE : View.VISIBLE);
        // Taking the key away switches the mode off, and the switch shows what is kept.
        adultSwitch.setChecked(Settings.getBoolean(Settings.WALLHAVEN_ADULT, false));
    }

    private void apply() {
        boolean changed = query.categories != categories || query.portrait != portrait || query.adultPurity != adultPurity;
        // Reset also gives a sorting chip tapped earlier back to the app's choice.
        if (automaticSorting && query.sortingChosen) {
            query.useAutomaticSorting();
            changed = true;
        }
        query.categories = categories;
        query.portrait = portrait;
        query.adultPurity = adultPurity;
        sheet.dismiss();
        if (changed) onChanged.run();
    }

    private void confirmRemove() {
        new ArsoundDialog(ui.activity)
                .setTitle(text("Убрать обои?", "Remove the wallpaper?"))
                .setMessage(text("Картинка удаляется с телефона, фон снова как у темы.",
                        "The picture is deleted from the phone and the background is the theme's again."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(text("Убрать", "Remove"), (dialog, which) -> {
                    WallpaperStore.removeInBackground(null);
                    Toast.makeText(ui.activity, text("Обои убраны", "Wallpaper removed"), Toast.LENGTH_SHORT).show();
                    sheet.dismiss();
                })
                .show();
    }

    private TextView heading(String label) {
        TextView heading = ui.createText("H4.Secondary", label);
        heading.setPadding(0, ui.dp(14), 0, ui.dp(6));
        return heading;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(ui.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addChip(LinearLayout row, TextView chip, int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = index == 0 ? 0 : ui.dp(8);
        row.addView(chip, params);
    }

    private View button(String label, boolean main, View.OnClickListener onClick) {
        View button = ui.createPillButton(label, main, onClick);
        ((TextView) button).setMinHeight(ui.dp(48));
        return button;
    }
}
