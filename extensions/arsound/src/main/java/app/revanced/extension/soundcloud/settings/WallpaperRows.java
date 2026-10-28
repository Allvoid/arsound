package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The wallpaper rows of the appearance screen: the switch, then a small phone with the whole picture as it lies behind
 * the screens next to the dim and the blur sliders, and the buttons that choose another picture or remove it. The
 * Wallhaven key and the 18+ switch are not here: they moved into the filters of the catalogue
 * ({@link WallhavenFilterSheet}), where they are used.
 */
final class WallpaperRows {
    private static final int PHONE_WIDTH_DP = 100;

    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    WallpaperRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    /**
     * The wallpaper: the switch, the small phone with the sliders, and the buttons. With no picture chosen yet the
     * sliders and the phone are not shown, but the search still knows them; the screen is built again once a picture
     * is chosen.
     */
    void addTo(LinearLayout list) {
        // The search finds the whole group by these words, which the rows do not all say.
        ui.recordForSearch(text("Обои", "Wallpaper"), text("Фон приложения: картинка, Wallhaven, затемнение, размытие, ключ API, 18+",
                "App background picture: Wallhaven, dim, blur, API key, 18+"));
        ui.recordForSearch(text("API-ключ Wallhaven", "Wallhaven API key"), text("Нужен только для картинок 18+", "Needed for 18+ pictures only"));
        ui.recordForSearch(text("Показывать 18+", "Show 18+"), text("Картинки Sketchy и NSFW в каталоге Wallhaven", "Sketchy and NSFW pictures in the Wallhaven catalogue"));
        ui.recordForSearch(text("Обои Wallhaven", "Wallhaven wallpapers"),
                text("Каталог wallhaven.cc: поиск, популярное, случайные. Нужен интернет.",
                        "The wallhaven.cc catalogue: search, popular, random. Needs the internet."));
        list.addView(ui.createSubHeading(text("Обои", "Wallpaper")));
        boolean chosen = WallpaperStore.has();

        list.addView(ui.createToggleRow(
                text("Показывать обои", "Show the wallpaper"),
                text("Картинка за экранами Arsound и за главными экранами SoundCloud: главной, библиотекой, поиском, "
                                + "плейлистами. Плеер, очередь, мини-плеер, нижняя панель и окна остаются без картинки. "
                                + "Включение и выключение перезапускает главный экран.",
                        "The picture behind Arsound's screens and SoundCloud's main screens: home, library, search, "
                                + "playlists. The player, the queue, the mini player, the tab bar and dialogs stay plain. "
                                + "Switching it on or off rebuilds the main screen."),
                WallpaperStore.isActive(),
                (button, checked) -> {
                    if (checked && !WallpaperStore.has()) {
                        // Nothing to show yet.
                        button.setChecked(false);
                        Toast.makeText(activity, text("Сначала выберите обои ниже", "Choose a wallpaper below first"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (Settings.getBoolean(Settings.WALLPAPER_ENABLED, false) == checked) return;
                    Settings.putBoolean(Settings.WALLPAPER_ENABLED, checked);
                    WallpaperStore.refresh();
                    activity.recreate();
                }
        ).icon("wallpaper"));

        if (chosen) {
            list.addView(createPictureCard());
            TextView hint = ui.createText("Body.Secondary", text(
                    "Затемнение меняется сразу, размытие применяется, когда отпустите ползунок.",
                    "The dim changes at once, the blur applies when you let go of the slider."));
            hint.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dp(4));
            list.addView(hint);
        } else {
            ui.recordForSearch(text("Затемнение обоев", "Wallpaper dim"), "");
            ui.recordForSearch(text("Размытие обоев", "Wallpaper blur"), "");
            TextView empty = ui.createText("Body.Secondary", text(
                    "Обои не выбраны. Каталог Wallhaven: поиск, популярное, случайные. Нужен интернет.",
                    "No wallpaper yet. The Wallhaven catalogue: search, popular, random. Needs the internet."));
            empty.setPadding(ui.dimen("spacing_m"), ui.dp(4), ui.dimen("spacing_m"), ui.dp(4));
            list.addView(empty);
        }
        list.addView(createButtons(chosen));
    }

    /** The small phone and the two sliders beside it. */
    private View createPictureCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dimen("spacing_m"), ui.dimen("spacing_s"), ui.dimen("spacing_m"), ui.dimen("spacing_s"));
        card.addView(createPhone());

        LinearLayout sliders = ui.createColumn();
        // The slider rows bring a side padding of their own, which is taken away here.
        int side = ui.dimen("spacing_m");
        View dim = ui.createSliderRow(text("Затемнение обоев", "Wallpaper dim"), "",
                95, WallpaperStore.dim(), value -> value + " %", value -> {
                    WallpaperStore.setDim(value);
                    // The pictures on this screen follow the dim live, so it must not rebuild itself for it when it is shown again.
                    activity.markWallpaperSeen();
                }, null, null);
        View blur = ui.createSliderRow(text("Размытие обоев", "Wallpaper blur"), "",
                100, WallpaperStore.blur(), value -> value + " %", value -> {
                }, value -> {
                    Settings.putLong(Settings.WALLPAPER_BLUR, value);
                    Utils.runOnBackgroundThread(() -> {
                        WallpaperStore.rebake(activity.getApplicationContext());
                        // This screen shows the new picture at once, so it must not rebuild itself for it.
                        Utils.runOnMainThread(activity::markWallpaperSeen);
                    });
                }, null);
        sliders.addView(dim);
        sliders.addView(blur);
        LinearLayout.LayoutParams slidersParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slidersParams.leftMargin = ui.dp(12) - side;
        slidersParams.rightMargin = -side;
        card.addView(sliders, slidersParams);
        return card;
    }

    /**
     * A small phone with the wallpaper drawn as the screens draw it, the dim over it and a line of text to judge the dim
     * by. The picture is baked in the shape of this phone's screen, so the whole of it is seen, not a strip.
     */
    private View createPhone() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float shape = Math.max(metrics.widthPixels, metrics.heightPixels) / (float) Math.min(metrics.widthPixels, metrics.heightPixels);
        FrameLayout phone = new FrameLayout(activity);
        Drawable picture = WallpaperStore.background(activity);
        phone.setBackground(picture != null ? picture : new ColorDrawable(ArsoundTheme.palette(activity, "highlight")));
        float radius = ui.dp(18);
        phone.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        phone.setClipToOutline(true);
        TextView sample = ui.createText("H4.Primary", text("Текст на обоях", "Text on the wallpaper"));
        sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        phone.addView(sample, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        phone.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(PHONE_WIDTH_DP), Math.round(ui.dp(PHONE_WIDTH_DP) * shape)));

        // The frame of the phone: a thin edge over the clipped picture.
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(radius);
        edge.setStroke(Math.max(2, ui.dp(2)), (ArsoundTheme.palette(activity, "secondary") & 0x00FFFFFF) | 0x66000000);
        phone.setForeground(edge);
        return phone;
    }

    /** "Choose" (or "Change") opens the Wallhaven catalogue; "Remove" asks and deletes the picture. */
    private View createButtons(boolean chosen) {
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(ui.dimen("spacing_m") - ui.dp(8), ui.dp(4), ui.dimen("spacing_m"), ui.dp(8));
        TextView choose = (TextView) ui.createPillButton(
                chosen ? text("Изменить", "Change") : text("Выбрать на Wallhaven", "Choose on Wallhaven"), true,
                v -> activity.startActivity(ui.screenIntent(WallhavenScreen.ID)));
        choose.setContentDescription(text("Обои Wallhaven", "Wallhaven wallpapers"));
        choose.setMinHeight(ui.dp(48));
        buttons.addView(choose);
        if (chosen) {
            TextView remove = (TextView) ui.createPillButton(text("Убрать", "Remove"), false, v -> new ArsoundDialog(activity)
                    .setTitle(text("Убрать обои?", "Remove the wallpaper?"))
                    .setMessage(text("Картинка удаляется с телефона, фон снова как у темы.",
                            "The picture is deleted from the phone and the background is the theme's again."))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(text("Убрать", "Remove"), (dialog, which) -> {
                        WallpaperStore.remove();
                        activity.recreate();
                    })
                    .show());
            remove.setMinHeight(ui.dp(48));
            buttons.addView(remove);
        }
        return buttons;
    }
}
