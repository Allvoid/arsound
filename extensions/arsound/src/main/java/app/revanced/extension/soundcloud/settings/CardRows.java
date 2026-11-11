package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;

import app.revanced.extension.soundcloud.theme.CardLook;
import app.revanced.extension.soundcloud.theme.CardSurfaces;

/**
 * "Cards and outlines" on the appearance screen: the switch, and under it the roundness of the corners, the outline
 * and how solid the fill is. The cards of this very screen follow the choices at once (the screen is built again), so
 * it is its own preview; the Library and the track lists follow when the user goes back to them
 * ({@link CardSurfaces#onActivityResumed}).
 */
final class CardRows {
    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    CardRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    void addTo(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Карточки и рамки", "Cards and outlines")));
        SubRows options = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Карточки в списках и настройках", "Cards in lists and in settings"),
                text("Строки библиотеки, треки и плейлисты в списках, плитки каруселей, строки настроек и меню SoundCloud "
                                + "лежат на скруглённых подложках, как строки этих настроек. Цвета берутся из темы, поэтому карточки подходят и тёмному, и светлому "
                                + "оформлению, и обоям. Выключено — строки SoundCloud остаются как были.",
                        "The rows of the Library, the tracks and playlists in lists, the tiles of carousels and the rows of "
                                + "SoundCloud's own settings and menus lie on rounded cards, like the rows of these settings. The colours come from the theme, so the cards fit the dark "
                                + "and the light look and a wallpaper. Off, SoundCloud's rows stay as they were."),
                BoolSettings.CARDS_ENABLED,
                options,
                (button, checked) -> changed()
        ).icon("theme").keywords(text("карточки рамки обводка подложка скругление", "cards outlines border panel rounded")));

        options.addView(ui.createChoiceRow(
                text("Скругление углов", "Corner roundness"),
                text("У карточек библиотеки, списков и этих настроек.", "Of the cards of the Library, the lists and these settings."),
                Settings.CARDS_RADIUS, CardLook.DEFAULT_RADIUS_DP, CardLook.RADIUS_CHOICES_DP,
                new String[]{text("Почти прямые", "Nearly square"), text("Небольшое", "Slight"),
                        text("Как в настройках", "As in the settings"), text("Сильное", "Strong")},
                this::changed));
        options.addView(ui.createChoiceRow(
                text("Рамка", "Outline"),
                text("Тонкая линия по краю карточки.", "A thin line along the edge of a card."),
                Settings.CARDS_OUTLINE, CardLook.OUTLINE_ACCENT,
                new long[]{CardLook.OUTLINE_ACCENT, CardLook.OUTLINE_PLAIN, CardLook.OUTLINE_NONE},
                new String[]{text("В цвет темы", "In the theme's accent"), text("Нейтральная", "Plain"),
                        text("Без рамки", "None")},
                this::changed));
        options.addView(ui.createSliderRow(
                text("Плотность подложки", "Fill"),
                text("Насколько карточка закрывает то, что за ней: обои или анимацию темы. 0 % — только рамка.",
                        "How much a card covers what is behind it: the wallpaper or the theme's animation. 0 % is the outline alone."),
                100, (int) Settings.getLong(Settings.CARDS_OPACITY, CardLook.DEFAULT_OPACITY), value -> value + " %",
                value -> {
                }, value -> {
                    Settings.putLong(Settings.CARDS_OPACITY, value);
                    changed();
                }, null));
        list.addView(options);
    }

    /** The cards of this screen are drawn again with the new look. */
    private void changed() {
        CardSurfaces.onLookChanged();
        activity.recreate();
    }
}
