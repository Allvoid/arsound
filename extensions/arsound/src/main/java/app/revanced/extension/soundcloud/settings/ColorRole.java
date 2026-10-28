package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.res.Resources;
import android.graphics.Color;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.CustomTheme;
import app.revanced.extension.soundcloud.theme.PaletteEngine;
import app.revanced.extension.soundcloud.theme.RuntimeThemes;

/**
 * One of the three colours of the custom theme the editor changes. Each role knows where its colour sits in the theme,
 * its name, which named ready colours to offer, which part of the colour square suits it ({@link ColorRange}), which
 * look (dark or light) it is judged in, and what the theme changed in it.
 */
enum ColorRole {
    ACCENT {
        @Override
        String title() {
            return text("Акцент", "Accent");
        }

        @Override
        String get(CustomTheme theme) {
            return theme.accent;
        }

        @Override
        CustomTheme with(CustomTheme theme, String hex) {
            return theme.withAccent(hex);
        }

        @Override
        ColorPreset[] presets(Resources resources) {
            // The accent of the wallpaper (Android 12+) is one more ready colour.
            String system = RuntimeThemes.systemAccent(resources);
            if (system == null) return accentPresets();
            ColorPreset[] base = accentPresets();
            ColorPreset[] presets = new ColorPreset[base.length + 1];
            System.arraycopy(base, 0, presets, 0, base.length);
            presets[base.length] = new ColorPreset(text("Цвет обоев Android", "Android wallpaper colour"), system);
            return presets;
        }

        @Override
        ColorRange range() {
            return ColorRange.FULL;
        }

        @Override
        boolean night(CustomTheme theme) {
            return !CustomTheme.MODE_LIGHT.equals(theme.mode);
        }

        @Override
        String note(String hex, ArsoundTheme.Theme theme, boolean night) {
            int wanted = Color.parseColor(hex) & 0xFFFFFF;
            return (theme.color(night, "special") & 0xFFFFFF) == wanted ? null
                    : text("Акцент подправлен, чтобы читаться на фоне.", "The accent was adjusted to read on the background.");
        }
    },
    DARK_BG {
        @Override
        String title() {
            return text("Тёмный фон", "Dark background");
        }

        @Override
        String get(CustomTheme theme) {
            return theme.darkBg;
        }

        @Override
        CustomTheme with(CustomTheme theme, String hex) {
            return theme.withDarkBg(hex);
        }

        @Override
        ColorPreset[] presets(Resources resources) {
            return new ColorPreset[]{
                    new ColorPreset(text("Графит", "Graphite"), "#121212"),
                    new ColorPreset(text("Чёрный", "Black"), "#000000"),
                    new ColorPreset(text("Полночь", "Midnight"), "#0D1117"),
                    new ColorPreset(text("Глубокий синий", "Deep blue"), "#0A1117"),
                    new ColorPreset(text("Уголь с красным", "Red charcoal"), "#0E0809"),
                    new ColorPreset(text("Хвоя", "Pine"), "#0D110F"),
                    new ColorPreset(text("Слива", "Plum"), "#15121C"),
                    new ColorPreset(text("Ультрафиолет", "Ultraviolet"), "#100C18"),
                    new ColorPreset(text("Кофе", "Coffee"), "#14110F"),
                    new ColorPreset(text("Океан", "Ocean"), "#101820")};
        }

        @Override
        ColorRange range() {
            return ColorRange.DARK;
        }

        @Override
        boolean night(CustomTheme theme) {
            return true;
        }
    },
    LIGHT_BG {
        @Override
        String title() {
            return text("Светлый фон", "Light background");
        }

        @Override
        String get(CustomTheme theme) {
            return theme.lightBg;
        }

        @Override
        CustomTheme with(CustomTheme theme, String hex) {
            return theme.withLightBg(hex);
        }

        @Override
        ColorPreset[] presets(Resources resources) {
            return new ColorPreset[]{
                    new ColorPreset(text("Белый", "White"), "#FFFFFF"),
                    new ColorPreset(text("Лёд", "Ice"), "#F7F9FC"),
                    new ColorPreset(text("Туман", "Mist"), "#F5F9FB"),
                    new ColorPreset(text("Мята", "Mint"), "#F7FAF8"),
                    new ColorPreset(text("Роза", "Rose"), "#FEF8F9"),
                    new ColorPreset(text("Сакура", "Sakura"), "#FFF7FA"),
                    new ColorPreset(text("Лаванда", "Lavender"), "#F9F8FC"),
                    new ColorPreset(text("Пергамент", "Parchment"), "#FAF6EE"),
                    new ColorPreset(text("Серый", "Grey"), "#F2F2F2")};
        }

        @Override
        ColorRange range() {
            return ColorRange.LIGHT;
        }

        @Override
        boolean night(CustomTheme theme) {
            return false;
        }
    };

    private static ColorPreset[] accentPresets() {
        return new ColorPreset[]{
                new ColorPreset("SoundCloud", "#FF5500"),
                new ColorPreset(text("Алый", "Scarlet"), "#E0242F"),
                new ColorPreset(text("Коралл", "Coral"), "#FF6B6B"),
                new ColorPreset(text("Янтарь", "Amber"), "#FFB020"),
                new ColorPreset(text("Лайм", "Lime"), "#A3FF3D"),
                new ColorPreset(text("Мята", "Mint"), "#34E0A1"),
                new ColorPreset(text("Бирюза", "Turquoise"), "#2DD4BF"),
                new ColorPreset(text("Лёд", "Ice"), "#9FDDF0"),
                new ColorPreset(text("Синий", "Blue"), "#4C9AFF"),
                new ColorPreset(text("Индиго", "Indigo"), "#6366F1"),
                new ColorPreset(text("Лаванда", "Lavender"), "#A78BFA"),
                new ColorPreset(text("Сакура", "Sakura"), "#FF85BE"),
                new ColorPreset(text("Белый", "White"), "#FFFFFF")};
    }

    /** The name of the colour, as the tile and the picker call it. */
    abstract String title();

    /** The colour of this role in the theme, as #RRGGBB. */
    abstract String get(CustomTheme theme);

    /** The theme with this role's colour replaced. */
    abstract CustomTheme with(CustomTheme theme, String hex);

    /** The named ready colours the picker offers. */
    abstract ColorPreset[] presets(Resources resources);

    /** The part of the colour square the picker shows for this role. */
    abstract ColorRange range();

    /** Whether the colour is judged on the dark look, for a theme being edited. */
    abstract boolean night(CustomTheme theme);

    /**
     * What the theme changed in the picked colour to keep the app readable, or null if nothing. The default is for the
     * backgrounds.
     */
    String note(String hex, ArsoundTheme.Theme theme, boolean night) {
        int wanted = Color.parseColor(hex) & 0xFFFFFF;
        if ((theme.color(night, "surface") & 0xFFFFFF) != wanted) {
            return night ? text("Фон оставлен тёмным: на светлом не видны белые значки и текст.", "The background is kept dark: white icons and text would vanish on a light one.")
                    : text("Фон оставлен светлым: на тёмном не видны тёмные значки.", "The background is kept light: dark icons would vanish on a dark one.");
        }
        // The background itself stayed, but the text had to move to read on the cards above it.
        return night && PaletteEngine.darkTextAdjusted(hex)
                ? text("Цвет текста слегка подправлен, чтобы читаться на этом фоне.", "The text colour is slightly adjusted to read on this background.")
                : null;
    }
}
