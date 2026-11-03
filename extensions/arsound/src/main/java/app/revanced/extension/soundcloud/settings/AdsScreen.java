package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.Toast;

/** Ads, subscription offers, the Upgrade tab, the download buttons of SoundCloud and the screenshot sheet. */
final class AdsScreen extends SectionScreen {
    static final String ID = "ads";

    AdsScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Реклама и подписка", "Ads and subscription");
    }

    @Override
    String description() {
        return text("Реклама, предложения Go и Go+, вкладка Upgrade, кнопки скачивания, окно скриншота",
                "Ads, Go and Go+ offers, Upgrade tab, download buttons, screenshot sheet");
    }

    @Override
    String summary() {
        return text("Блок рекламы: ", "Ad block: ")
                + (BoolSettings.BLOCK_PLAYBACK_ADS.get() ? text("вкл", "on") : text("выкл", "off"));
    }

    @Override
    String iconKey() {
        return "ads";
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Реклама", "Ads")));
        list.addView(ui.createToggleRow(
                text("Блокировать рекламу", "Block ads"),
                text("Без аудио- и видеорекламы между треками и без рекламных баннеров в плеере, ленте, библиотеке, "
                                + "плейлистах и профилях. Применится после перезапуска SoundCloud.",
                        "No audio or video ads between tracks and no banner ads in the player, feed, library, "
                                + "playlists and profiles. Applies after restarting SoundCloud."),
                BoolSettings.BLOCK_PLAYBACK_ADS,
                (button, checked) -> {
                    Toast.makeText(activity,
                            text("Перезапустите SoundCloud, чтобы применить", "Restart SoundCloud to apply"),
                            Toast.LENGTH_SHORT).show();
                }
        ).icon("ads").brief(text("Без аудио-, видеорекламы и баннеров.", "No audio, video or banner ads.")));

        list.addView(ui.createSubHeading(text("Подписка Go и Go+", "Go and Go+ subscription")));
        list.addView(ui.createToggleRow(
                text("Скрывать предложения подписки", "Hide subscription offers"),
                text("Не показывает экран покупки SoundCloud Go и Go+, всплывающие предложения и баннер подписки "
                                + "на главной. Купить подписку в моде всё равно нельзя.",
                        "Hides the SoundCloud Go and Go+ purchase screen, popups and the subscription banner on the "
                                + "home screen. A subscription cannot be bought in the mod anyway."),
                BoolSettings.HIDE_SUBSCRIPTION_OFFERS
        ).icon("subscription").brief(text("Без экрана покупки Go и Go+ и всплывающих предложений.",
                "No Go and Go+ purchase screen or popup offers.")));
        list.addView(ui.createToggleRow(
                text("Кнопки скачивания SoundCloud — через Arsound", "SoundCloud download buttons use Arsound"),
                text("«Скачать» в меню трека, плейлиста и альбома, на странице трека, круглая кнопка над плейлистом "
                                + "и «Понравившимися» скачивают файлы через Arsound, а не предлагают подписку Go. "
                                + "С подпиской SoundCloud скачивает сам, предложения нет — его скачивание "
                                + "работает как обычно. Выключите, чтобы вернуть кнопкам поведение SoundCloud целиком.",
                        "\"Download\" in the menu of a track, a playlist and an album, on the track page, and the round "
                                + "button above a playlist and your likes download files with Arsound instead of "
                                + "offering a Go subscription. With a subscription SoundCloud downloads by itself and "
                                + "offers nothing, so its download works as usual. Turn off to give the buttons back "
                                + "to SoundCloud entirely."),
                BoolSettings.INTERCEPT_DOWNLOAD_BUTTONS
        ).icon("storage").brief(text("Кнопки «Скачать» качают файлы через Arsound.",
                "The Download buttons save files with Arsound.")));

        list.addView(ui.createSubHeading(text("Лишние окна", "Extra windows")));
        list.addView(ui.createToggleRow(
                text("Скрыть баннер импорта плейлистов", "Hide the playlist import banner"),
                text("Убирает из библиотеки баннер «Transfer your gems». Кнопка «закрыть» у него временная: "
                                + "через несколько дней баннер возвращается сам. Применится после перезапуска.",
                        "Removes the \"Transfer your gems\" banner from the library. Its close button only hides it "
                                + "for a few days, after which it comes back. Applies after a restart."),
                BoolSettings.HIDE_IMPORT_BANNER
        ).icon("import").brief(text("Убирает из библиотеки баннер «Transfer your gems».",
                "Removes the \"Transfer your gems\" banner from the library.")));
        list.addView(ui.createToggleRow(
                text("Окно «Поделиться скриншотом»", "\"Share screenshot\" sheet"),
                text("После скриншота SoundCloud открывает окно, чтобы поделиться им со ссылкой на трек (Android 14 "
                                + "и новее). Выключено — скриншот делается без окна. Включено — окно открывается один "
                                + "раз, а не дважды, как в оригинале на экране плеера. Применится после перезапуска.",
                        "After a screenshot SoundCloud opens a sheet to share it with a link to the track (Android 14 "
                                + "and later). Off: screenshots are taken without the sheet. On: the sheet opens once, "
                                + "not twice as in the original on the player screen. Applies after a restart."),
                BoolSettings.SCREENSHOT_SHARE
        ).icon("screenshot").brief(text("После скриншота предлагает поделиться им со ссылкой на трек.",
                "Offers to share a screenshot with a link to the track.")));
    }
}
