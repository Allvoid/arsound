package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.LocaleManager;
import android.os.Build;
import android.os.LocaleList;
import android.widget.LinearLayout;

import java.util.Arrays;

import app.revanced.extension.soundcloud.player.NotificationArtwork;
import app.revanced.extension.soundcloud.settings.search.SearchEntry;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;
import app.revanced.extension.soundcloud.widget.WidgetHeart;

/** Theme, wallpaper, app icon and language. */
final class AppearanceScreen extends SectionScreen {
    static final String ID = "appearance";

    AppearanceScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Оформление", "Appearance");
    }

    @Override
    String description() {
        return text("Тема, обои, анимация, плеер, иконка приложения, язык", "Theme, wallpaper, animation, player, app icon, language");
    }

    @Override
    String iconKey() {
        return "appearance";
    }

    @Override
    String keywords() {
        return text("тема цвет обои фон иконка язык анимация плеер оформление", "theme color wallpaper background icon language animation player appearance");
    }

    @Override
    String summary() {
        String current = ArsoundTheme.current();
        String name = ArsoundTheme.SOUNDCLOUD.equals(current) ? "SoundCloud" : current;
        for (ArsoundTheme.Theme theme : ArsoundTheme.all(activity)) {
            if (theme.id.equals(current)) name = theme.name();
        }
        return text("Тема ", "Theme ") + name + text(" · обои ", " · wallpaper ")
                + (WallpaperStore.isActive() ? text("вкл", "on") : text("выкл", "off"));
    }

    @Override
    void fill(LinearLayout list) {
        // The theme picker is not a row of its own: the search finds it by the theme names, Monet's "Material You" and its colours.
        StringBuilder themeNames = new StringBuilder("SoundCloud");
        for (ArsoundTheme.Theme theme : ArsoundTheme.all(activity)) themeNames.append(", ").append(theme.name());
        SearchEntry themeEntry = ui.recordForSearch(text("Тема", "Theme"),
                themeNames + text(", Material You, цвета обоев", ", Material You, wallpaper colours"));
        if (themeEntry != null) themeEntry.addKeywords(text("оформление стиль цвет внешний вид тёмная светлая", "appearance style colour look dark light"));
        addThemeRows(list);
        new WallpaperRows(ui).addTo(list);
        addPlayerRows(list);
        addHomeRows(list);

        list.addView(ui.createSubHeading(text("Приложение", "App")));
        addLanguageRow(list);
        list.addView(ui.openScreenRow(text("Иконка приложения", "App icon"),
                text("Цвет и градиент значка на рабочем столе", "Colour and gradient of the home screen icon"),
                AppIconScreen.ID).icon("appicon"));
    }

    /** The theme picker, the colours of the own theme and the animated background. */
    private void addThemeRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Тема", "Theme")));
        list.addView(new ThemeDropdown(ui).create());
        list.addView(ui.openScreenRow(text("Цвета своей темы", "Custom theme colours"),
                text("Выберите акцент и фон, остальное подберётся само. Тема «Своя тема» из списка выше.",
                        "Pick an accent and a background and the rest follows. This is the \"Custom theme\" of the list above."),
                CustomThemeScreen.ID).icon("theme"));
        SubRows effectOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Анимация темы", "Theme animation"),
                text("Лёгкий живой фон: искры у «Алого», звёзды у «Кобальта», светлячки у «Мяты», лепестки у «Сакуры», "
                                + "сканер у «Лайма», снег и иней у «Зимы». Замирает в режиме экономии батареи и когда анимации выключены в Android.",
                        "A light living background: sparks for Scarlet, stars for Cobalt, fireflies for Mint, petals for "
                                + "Sakura, a scanner for Lime, snow and frost for Winter. Stands still in battery saver mode and when Android's "
                                + "animations are off."),
                BoolSettings.THEME_EFFECTS,
                effectOptions,
                (button, checked) -> activity.recreate()
        ).icon("animation").brief(text("Лёгкий живой фон: искры, звёзды, снег.", "A light living background: sparks, stars, snow.")));
        effectOptions.addView(ui.createChoiceRow(text("Количество частиц", "Particle amount"), null,
                Settings.THEME_EFFECTS_DENSITY, 100, new long[]{50, 100, 160},
                new String[]{text("Меньше", "Fewer"), text("Как в макете", "As designed"), text("Больше", "More")},
                activity::recreate));
        list.addView(effectOptions);
    }

    /** The new player and what belongs to it. */
    private void addPlayerRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Плеер", "Player")));
        // Part of the new player, so it shows only with it.
        SubRows playerOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Новый плеер (бета)", "New player (beta)"),
                text("Полноэкранный плеер в стиле темы: обложка плавно уходит в цвет темы, название и исполнитель без "
                                + "чёрных плашек, волна и время в акценте, кнопки — тёмное стекло, в уведомлении — фон темы. "
                                + "Работает с темами Arsound, "
                                + "не с видом SoundCloud. Приложение перезапустится.",
                        "The full screen player in the theme's style: the artwork fades into the theme's colour, the title "
                                + "and artist without black boxes, the waveform and time in the accent, the buttons in dark "
                                + "glass, the theme's background in the notification. Works with the Arsound themes, not with the SoundCloud look. The app restarts."),
                ArsoundTheme.isPlayerSkinEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(ArsoundTheme.PLAYER_SKIN_KEY, checked);
                    if (!ArsoundTheme.SOUNDCLOUD.equals(ArsoundTheme.current())) ArsoundTheme.restartApp(activity);
                    playerOptions.follow(checked);
                }
        ).icon("player").brief(text("Полноэкранный плеер в стиле темы.", "A full-screen player in the theme's style.")));
        playerOptions.addView(ui.createToggleRow(
                text("Плеер в уведомлении под цвет трека", "Notification player in the track's colours"),
                text("Плеер в уведомлении и на экране блокировки берёт обложку и цвета играющего трека. Выключено — "
                                + "фон и цвета темы; на телефонах, где шторка показывает обложку (OnePlus, OPPO, vivo и "
                                + "другие), обложка там тоже будет в цветах темы. Действует со следующего трека.",
                        "The player in the notification and on the lock screen takes the cover and colours of the "
                                + "playing track. Off: the theme's background and colours; on phones whose control centre "
                                + "shows the cover (OnePlus, OPPO, vivo and others) the cover there is in the theme's "
                                + "colours too. Applies from the next track."),
                NotificationArtwork.isTrackColorsEnabled(),
                (button, checked) -> Settings.putBoolean(
                        NotificationArtwork.TRACK_COLORS_KEY, checked)
        ).brief(text("Обложка и цвета трека в уведомлении и на экране блокировки.",
                "The track's cover and colours in the notification and on the lock screen.")));
        playerOptions.follow(ArsoundTheme.isPlayerSkinEnabled());
        list.addView(playerOptions);
        list.addView(ui.createToggleRow(
                text("Плавное сворачивание плеера", "Smooth player collapse"),
                text("Плеер сворачивается и раскрывается без рывков: пока он движется, Arsound не перерисовывает "
                                + "обложку зря и ставит анимацию темы на паузу. Если что-то выглядит не так, "
                                + "выключите: плеер станет таким, как в SoundCloud. Действует со следующего движения плеера.",
                        "The player collapses and opens without stutter: while it moves, Arsound does not redraw the "
                                + "artwork needlessly and pauses the theme animation. If something looks wrong, turn it "
                                + "off: the player goes back to SoundCloud's own behaviour. Applies from the next move of the player."),
                BoolSettings.SMOOTH_PLAYER_COLLAPSE
        ).icon("animation").brief(text("Плеер сворачивается без рывков.", "The player collapses without stutter.")));
    }

    /** The home screen header and the widget. */
    private void addHomeRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Главная и виджет", "Home and widget")));
        list.addView(ui.createToggleRow(
                text("Без приветствия на главной", "No greeting on the home screen"),
                text("Убирает надпись «Привет, имя / Что послушаем?» над главной, которую ставят некоторые темы: "
                                + "список начинается сразу под верхней панелью. Главная меняется, когда её откроют снова.",
                        "Removes the \"Hello, name / What shall we play?\" heading some themes put above the home "
                                + "screen: the list starts right under the top bar. The home screen changes when it is "
                                + "opened again."),
                BoolSettings.HIDE_HOME_GREETING
        ).icon("home").brief(text("Убирает «Привет, … Что послушаем?».", "Removes \"Hello, … What shall we play?\".")));
        list.addView(ui.createToggleRow(
                text("Без картинки в шапке «Ваши лайки»", "No picture at the top of \"Your likes\""),
                text("Убирает сердца за заголовком «Ваши лайки» и карточки за заголовком покупок: остаётся фон темы. "
                                + "Выключено — картинка в цвете темы. Открытая страница меняется, когда её откроют снова.",
                        "Removes the hearts behind the \"Your likes\" title and the cards behind the purchases title: "
                                + "the theme's background stays. Off: the picture in the theme's colour. A page already "
                                + "open changes when it is opened again."),
                BoolSettings.HIDE_HEADER_ARTWORK
        ).icon("wallpaper").brief(text("Убирает сердца за заголовком «Ваши лайки».", "Removes the hearts behind \"Your likes\".")));
        list.addView(ui.createToggleRow(
                text("Сердечко в виджете под цвет обложки", "Widget heart in the cover colour"),
                text("Сердечко в виджете плеера на рабочем столе берёт цвет обложки играющего трека. У серой обложки и "
                                + "у трека без обложки — цвет акцента темы, а с видом SoundCloud — как у SoundCloud. "
                                + "Выключено — сердечко как у SoundCloud. Виджет обновляется сразу.",
                        "The heart of the player widget on the home screen takes the colour of the playing track's "
                                + "cover. For a grey cover or a track without one it takes the theme's accent, and with "
                                + "the SoundCloud look it stays as SoundCloud draws it. Off: SoundCloud's own heart. "
                                + "The widget updates at once."),
                WidgetHeart.isEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(
                            WidgetHeart.COVER_COLOR_KEY, checked);
                    WidgetHeart.refresh(activity);
                }
        ).icon("widget").brief(text("Сердечко виджета берёт цвет обложки играющего трека.",
                "The widget heart takes the colour of the playing track's cover.")));
    }

    /** The app language: as in the system, Russian or English. Android 13+ keeps it per app. */
    private void addLanguageRow(LinearLayout list) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        LocaleManager manager = activity.getSystemService(LocaleManager.class);
        String[] tags = {"", "ru", "en"};
        String[] labels = {text("Как в системе", "System default"), "Русский", "English"};
        String chosen = manager.getApplicationLocales().isEmpty() ? "" : manager.getApplicationLocales().get(0).getLanguage();
        int index = Arrays.asList(tags).indexOf(chosen);
        ActionRow row = ui.createActionRow(text("Язык приложения", "App language"), "", v ->
                new ArsoundDialog(activity)
                        .setTitle(text("Язык приложения", "App language"))
                        .setSingleChoiceItems(labels, Math.max(0, index), (dialog, which) -> {
                            dialog.dismiss();
                            manager.setApplicationLocales(tags[which].isEmpty()
                                    ? LocaleList.getEmptyLocaleList()
                                    : LocaleList.forLanguageTags(tags[which]));
                            // Android redraws SoundCloud's screens by itself, but Arsound reads the language once
                            // per start: a restart switches everything.
                            ArsoundTheme.restartApp(activity);
                        })
                        .show());
        row.icon("language").value(labels[Math.max(0, index)]);
        list.addView(row);
    }
}
