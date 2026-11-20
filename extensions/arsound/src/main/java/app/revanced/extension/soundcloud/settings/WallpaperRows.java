package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Outline;
import android.net.Uri;
import android.provider.OpenableColumns;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.BoundedCopy;
import app.revanced.extension.soundcloud.wallpaper.TopBarStyle;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The wallpaper rows of the appearance screen: the switch, then a small phone with the whole picture as it lies behind
 * the screens next to the dim and the blur sliders, the look of the top bar over the picture (small phones to choose
 * from, {@link TopBarPreview}), and the buttons that choose another picture or remove it. The catalogue behind the
 * first button has two sources, Wallhaven and Wikimedia Commons, which its own screen switches ({@link WallhavenScreen}).
 * The Wallhaven key and the 18+ switch are not here: they moved into the filters of the catalogue
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
        ui.recordForSearch(text("Обои", "Wallpaper"), text("Фон приложения: картинка, Wallhaven, Wikimedia Commons, Викисклад, "
                        + "затемнение, размытие, ключ API, 18+",
                "App background picture: Wallhaven, Wikimedia Commons, dim, blur, API key, 18+"));
        ui.recordForSearch(text("API-ключ Wallhaven", "Wallhaven API key"), text("Нужен только для картинок 18+", "Needed for 18+ pictures only"));
        ui.recordForSearch(text("Показывать 18+", "Show 18+"), text("Картинки Sketchy и NSFW в каталоге Wallhaven", "Sketchy and NSFW pictures in the Wallhaven catalogue"));
        ui.recordForSearch(text("Обои Wallhaven", "Wallhaven wallpapers"),
                text("Каталог wallhaven.cc: поиск, популярное, случайные. Нужен интернет.",
                        "The wallhaven.cc catalogue: search, popular, random. Needs the internet."));
        ui.recordForSearch(text("Обои Wikimedia Commons", "Wikimedia Commons wallpapers"),
                text("Бесплатные фото Викисклада: избранные и качественные, поиск по-русски и по-английски, без регистрации. "
                                + "Нужен интернет.",
                        "Free photos of Wikimedia Commons: featured and quality pictures, search in Russian or English, "
                                + "nothing to register. Needs the internet."));
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
            if (WallpaperStore.mediaFile() != null) {
                TextView moving = ui.createText("Body.Secondary", (WallpaperStore.isVideo()
                        ? text("Фон: видео. Идёт по кругу без звука, пока открыт главный экран; свёрнутое приложение, "
                                + "другие экраны Arsound и режим экономии батареи его останавливают, остаётся кадр. ",
                        "Background: a video. It loops with no sound while the main screen is open; the app in the "
                                + "background, other Arsound screens and battery saver stop it, and a frame stays. ")
                        : text("Фон: анимированная картинка. Играет, пока открыт главный экран; свёрнутое приложение, "
                                + "другие экраны Arsound и режим экономии батареи её останавливают. ",
                        "Background: an animated picture. It plays while the main screen is open; the app in the "
                                + "background, other Arsound screens and battery saver stop it. "))
                        + (app.revanced.extension.soundcloud.wallpaper.MediaWallpaper.canBlur()
                        ? text("Затемнение и размытие работают и на движущемся фоне.",
                        "The dim and the blur work on a moving background too.")
                        : text("Размытие на движущемся фоне работает с Android 12; на этом телефоне оно не применяется, "
                                + "затемнение работает.", "The blur on a moving background needs Android 12; on this "
                                + "phone it is not applied, the dim works.")));
                moving.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), ui.dp(4));
                list.addView(moving);
            }
            list.addView(createTopBarChooser());
        } else {
            ui.recordForSearch(text("Затемнение обоев", "Wallpaper dim"), "");
            ui.recordForSearch(text("Размытие обоев", "Wallpaper blur"), "");
            TextView empty = ui.createText("Body.Secondary", text(
                    "Обои не выбраны. Каталоги Wallhaven и Wikimedia Commons: поиск, популярное, случайные. Нужен интернет.",
                    "No wallpaper yet. The Wallhaven and Wikimedia Commons catalogues: search, popular, random. "
                            + "Needs the internet."));
            empty.setPadding(ui.dimen("spacing_m"), ui.dp(4), ui.dimen("spacing_m"), ui.dp(4));
            list.addView(empty);
        }
        addButtons(list, chosen);
    }

    /**
     * The look of the top bar over the wallpaper: a small phone for each style, with this wallpaper, a list scrolled
     * under the bar and the bar drawn as the app draws it; a tap chooses. Open screens follow when they are drawn again.
     */
    private View createTopBarChooser() {
        String title = text("Подложка верхней панели", "Top bar background");
        ui.recordForSearch(title, text("Полоса за кнопкой «назад», поиском в плейлисте и фильтром над обоями: как в SoundCloud, "
                        + "без подложки, полупрозрачная, затемнение, стекло. Шапка, панель поиска, liquid glass, прозрачность.",
                "The band behind the back button, the playlist search and the filter over the wallpaper: as in SoundCloud, "
                        + "none, translucent, fade, glass. Header, search bar, liquid glass, transparency."));
        SettingsGroup card = new SettingsGroup(ui).withoutDividers();
        card.setLayoutParams(SettingsGroup.pageParams(ui));
        card.setPadding(0, ui.dp(14), 0, ui.dp(14));

        TextView heading = ui.createText("Body.Primary", title);
        heading.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(2));
        card.addView(heading);
        // Read for the search, the screen needs only the words above: a preview is a wallpaper drawn for each style.
        if (ui.search.isIndexing()) return card;
        TextView description = ui.createText("Body.Secondary", TopBarStyle.current().description());
        description.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), 0);
        description.setMinLines(2);

        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int surface = ArsoundTheme.palette(activity, "surface");
        int width = ui.dp(104);
        TopBarStyle current = TopBarStyle.current();
        java.util.List<TopBarPreview> previews = new java.util.ArrayList<>();
        java.util.List<TextView> labels = new java.util.ArrayList<>();
        for (TopBarStyle style : TopBarStyle.values()) {
            LinearLayout column = ui.createColumn();
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setPadding(ui.dp(4), 0, ui.dp(4), 0);
            TopBarPreview preview = new TopBarPreview(activity, style, surface, ui.accent());
            preview.setChosen(style == current);
            column.addView(preview, new LinearLayout.LayoutParams(width, Math.round(width * TopBarPreview.TILE)));
            TextView name = ui.createText("Body.Secondary", style.label());
            name.setGravity(Gravity.CENTER);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setPadding(0, ui.dp(6), 0, 0);
            name.setMaxLines(2);
            // Words are not broken in the middle: a narrow label wraps between them.
            name.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
            name.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            name.setLayoutParams(new LinearLayout.LayoutParams(width + ui.dp(6), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (style == current) name.setTextColor(ui.accent());
            column.addView(name);
            column.setOnClickListener(v -> {
                style.choose();
                for (int i = 0; i < previews.size(); i++) {
                    boolean chosen = TopBarStyle.values()[i] == style;
                    previews.get(i).setChosen(chosen);
                    if (chosen) labels.get(i).setTextColor(ui.accent());
                    else ui.style(labels.get(i), "Body.Secondary");
                }
                description.setText(style.description());
            });
            previews.add(preview);
            labels.add(name);
            row.addView(column);
        }
        scroller.addView(row);
        // The chosen style is in sight when the screen opens, however far along the row it is.
        int chosenIndex = current.ordinal();
        scroller.post(() -> {
            View chosen = row.getChildAt(chosenIndex);
            if (chosen != null && chosen.getRight() > scroller.getWidth()) scroller.scrollTo(chosen.getLeft() - ui.dp(10), 0);
        });
        card.addView(scroller);
        card.addView(description);
        return card;
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

    /**
     * Where the picture comes from and how it goes: the catalogue (Wallhaven or Commons), a picture from the phone's gallery, and
     * (with a picture chosen) removing it. Rows of their own that say what they do: the two bare buttons "Change" and
     * "Remove" did not say what they changed or removed.
     */
    private void addButtons(LinearLayout list, boolean chosen) {
        list.addView(ui.createActionRow(
                chosen ? text("Другие обои из каталога", "Other wallpaper from the catalogue")
                        : text("Обои из каталога", "Wallpaper from the catalogue"),
                text("Wallhaven и Wikimedia Commons: поиск, популярное, случайные картинки. Нужен интернет.",
                        "Wallhaven and Wikimedia Commons: search, popular and random pictures. Needs the internet."),
                v -> activity.startActivity(ui.screenIntent(WallhavenScreen.ID))).icon("wallpaper").chevron());
        list.addView(ui.createActionRow(
                text("Свой фон: картинка, GIF или видео", "Your own background: a picture, a GIF or a video"),
                text("Любой файл с телефона: фото и картинки (JPG, PNG, WebP, HEIC, AVIF), анимация (GIF, анимированный WebP) "
                                + "и видео (mp4, webm, mkv, в том числе видеообои из приложений обоев). Видео идёт по кругу без "
                                + "звука и останавливается, когда приложение свёрнуто. Затемнение и размытие те же.",
                        "Any file on the phone: photos and pictures (JPG, PNG, WebP, HEIC, AVIF), animations (GIF, animated "
                                + "WebP) and videos (mp4, webm, mkv, including video wallpapers from wallpaper apps). A video "
                                + "loops with no sound and stops when the app is in the background. The dim and the blur stay."),
                v -> pickOwnPicture()).icon("import").chevron());
        if (!chosen) return;
        list.addView(ui.createActionRow(text("Убрать обои", "Remove the wallpaper"),
                text("Картинка удаляется с телефона, фон снова как у темы.",
                        "The picture is deleted from the phone and the background is the theme's again."),
                v -> new ArsoundDialog(activity)
                        .setTitle(text("Убрать обои?", "Remove the wallpaper?"))
                        .setMessage(text("Картинка удаляется с телефона, фон снова как у темы.",
                                "The picture is deleted from the phone and the background is the theme's again."))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Убрать", "Remove"), (dialog, which) ->
                                WallpaperStore.removeInBackground(() -> {
                                    if (!activity.isDestroyed()) activity.recreate();
                                }))
                        .show()).icon("trash").danger());
    }

    /** Opens the system's file picker for pictures and videos; the answer comes to {@link #onPicturePicked}. */
    private void pickOwnPicture() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            activity.startActivityForResult(intent, REQUEST_OWN_PICTURE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(activity, text("На телефоне нет приложения для выбора файлов", "No app on the phone picks files"),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The request code of {@link #pickOwnPicture}, for the screen's {@code onActivityResult}. */
    static final int REQUEST_OWN_PICTURE = 7101;

    /** The size the system names for the picked file, or -1 when it names none: then the copy counts the bytes itself. */
    private static long reportedSize(Context context, Uri picture) {
        try (Cursor cursor = context.getContentResolver().query(picture, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
            // Not every provider answers this query.
        }
        return -1;
    }

    /**
     * The picture the user picked: copied into the cache, kept as the wallpaper the same way a catalogue picture is
     * (an odd file is refused with a message), then the screen is built again with it.
     */
    static void onPicturePicked(ReVancedSettingsActivity activity, Uri picture) {
        Toast.makeText(activity, text("Ставлю фон…", "Setting the background…"), Toast.LENGTH_SHORT).show();
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            File copy = new File(context.getCacheDir(), "arsound-own-wallpaper.tmp");
            // A file the system says is too big is refused before a byte of it is copied.
            long reported = reportedSize(context, picture);
            boolean tooBig = reported > WallpaperStore.MAX_OWN_BYTES;
            // The copy needs room of its own: a full storage is told as that, not as an odd file.
            boolean noRoom = !tooBig && !OwnPicture.hasRoom(reported, OwnPicture.usableOf(context.getCacheDir()));
            boolean read = false;
            if (!tooBig && !noRoom) {
                try (InputStream input = context.getContentResolver().openInputStream(picture);
                     OutputStream output = new FileOutputStream(copy)) {
                    if (input != null) {
                        // The copy stops at the limit: a bigger file is not written to the end before it is refused.
                        tooBig = BoundedCopy.copy(input, output, WallpaperStore.MAX_OWN_BYTES) < 0;
                        read = !tooBig;
                    }
                } catch (Exception ex) {
                    noRoom = OwnPicture.isNoSpace(ex);
                    app.revanced.extension.shared.Logger.printException(() -> "Wallpaper: could not read the picked picture", ex);
                }
            }
            boolean full = noRoom;
            WallpaperStore.Result result = tooBig ? WallpaperStore.Result.TOO_BIG
                    : read ? WallpaperStore.installOwnMedia(context, copy) : WallpaperStore.Result.FAILED;
            boolean installed = result == WallpaperStore.Result.KEPT;
            copy.delete();
            Utils.runOnMainThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!installed) {
                    boolean russian = app.revanced.extension.soundcloud.shared.Lang.russian();
                    Toast.makeText(activity, result == WallpaperStore.Result.TOO_BIG ? OwnPicture.tooBigText(russian)
                                    : full ? OwnPicture.noRoomText(russian)
                                    : text("Не получилось открыть этот файл: нужна картинка, GIF или видео, которые умеет открыть Android",
                                    "Could not open this file: a picture, a GIF or a video that Android can open is needed"),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                activity.recreate();
            });
        });
    }
}
