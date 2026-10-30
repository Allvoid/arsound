package app.revanced.extension.soundcloud.wallpaper;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * What lies behind SoundCloud's top bar (the back button, "Search in …", the filter) over the wallpaper, chosen in
 * Appearance. Without a wallpaper the bar keeps SoundCloud's look whatever is chosen.
 */
public enum TopBarStyle {
    /** SoundCloud's own opaque band in the screen's colour. */
    SOUNDCLOUD(0, "Как в SoundCloud", "As in SoundCloud",
            "Сплошная полоса цвета темы: поиск читается всегда, обои сверху закрыты.",
            "A solid band in the theme's colour: the search always reads, the top of the wallpaper is covered."),
    /** No band: the buttons and the field keep their own backgrounds. */
    CLEAR(1, "Без подложки", "No band",
            "Полосы нет, обои видны целиком; под панелью проезжают треки списка.",
            "No band, the whole wallpaper shows; the tracks of the list pass under the bar."),
    /** The band at half strength. */
    TINT(2, "Полупрозрачная", "Translucent",
            "Та же полоса наполовину прозрачная: обои просвечивают, список под ней приглушён.",
            "The same band, half see-through: the wallpaper shows through, the list under it is muted."),
    /** Dark at the top, gone by the bottom. */
    FADE(3, "Затемнение", "Fade",
            "Тёмная вверху и тает книзу, без жёсткого края.",
            "Dark at the top and fading out towards the bottom, with no hard edge."),
    /** Frosted glass: the wallpaper under the bar strongly blurred, a light sheen and a bright edge. */
    GLASS(4, "Стекло", "Glass",
            "Матовое стекло: под панелью обои сильно размыты, сверху блик, по нижнему краю светлая линия.",
            "Frosted glass: the wallpaper under the bar is strongly blurred, with a sheen on top and a light line along the bottom.");

    public final long id;
    private final String russian, english, russianDescription, englishDescription;

    TopBarStyle(long id, String russian, String english, String russianDescription, String englishDescription) {
        this.id = id;
        this.russian = russian;
        this.english = english;
        this.russianDescription = russianDescription;
        this.englishDescription = englishDescription;
    }

    public String label() {
        return text(russian, english);
    }

    public String description() {
        return text(russianDescription, englishDescription);
    }

    /** The chosen style; SoundCloud's own until the user picks another. */
    public static TopBarStyle current() {
        long id = Settings.getLong(Settings.WALLPAPER_TOP_BAR, SOUNDCLOUD.id);
        for (TopBarStyle style : values()) if (style.id == id) return style;
        return SOUNDCLOUD;
    }

    public void choose() {
        Settings.putLong(Settings.WALLPAPER_TOP_BAR, id);
    }
}
