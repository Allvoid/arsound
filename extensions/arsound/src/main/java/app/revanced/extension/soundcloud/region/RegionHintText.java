package app.revanced.extension.soundcloud.region;

/**
 * What the hint dialog says. The names of screens and buttons are those of the Arsound settings (see
 * {@code ReVancedSettingsActivity}); when one is renamed there, it is renamed here.
 */
final class RegionHintText {
    private RegionHintText() {
    }

    /** One step of the text: a short lead-in in bold, then the explanation. The lead-in may be empty. */
    static final class Paragraph {
        final String lead;
        final String body;

        Paragraph(String lead, String body) {
            this.lead = lead;
            this.body = body;
        }
    }

    static String title(boolean russian) {
        return russian ? "Недоступно в вашей стране" : "Not available in your country";
    }

    /** Where the settings of Arsound are, as the first run of the app tells it. */
    static String settingsPath(boolean russian) {
        return russian
                ? "Библиотека → шестерёнка справа сверху → «Arsound»"
                : "Library → the gear at the top right → \"Arsound\"";
    }

    static Paragraph[] paragraphs(boolean russian) {
        String path = settingsPath(russian);
        if (russian) {
            return new Paragraph[]{
                    new Paragraph("", "Так SoundCloud помечает трек, когда считает, что ваш аккаунт или подключение "
                            + "из России или из другой страны, где у него есть блокировки. У других слушателей "
                            + "тот же трек может играть. Чтобы метка пропала:"),
                    new Paragraph("1. Включите VPN.",
                            "Выберите страну, где SoundCloud работает без ограничений, например Германию или Нидерланды, "
                                    + "и подождите пару секунд, пока соединение установится."),
                    new Paragraph("2. Очистите кэш.",
                            "Иначе SoundCloud помнит старые блокировки. Откройте настройки Arsound (" + path
                                    + "), раздел «Обновления и данные», кнопка «Сбросить данные SoundCloud». "
                                    + "SoundCloud перезапустится и заново загрузит библиотеку, поэтому нужен интернет. "
                                    + "Вход в аккаунт, настройки Arsound, скачанные и импортированные треки и порядок "
                                    + "плейлистов останутся."),
                    new Paragraph("3. Закройте дорогу российскому IP.",
                            "В настройках Arsound откройте раздел «Сеть» и включите «Не выходить в сеть с IP выбранных "
                                    + "стран» (Россия отмечена по умолчанию). Когда VPN выключен или отвалился, "
                                    + "Arsound не будет обращаться к SoundCloud с российского IP, и аккаунт не "
                                    + "пометят снова."),
            };
        }
        return new Paragraph[]{
                new Paragraph("", "This is how SoundCloud marks a track when it decides that your account or "
                        + "connection is from Russia or from another country where it has blocks. The same track may "
                        + "play for other listeners. To make the label go away:"),
                new Paragraph("1. Turn on a VPN.",
                        "Pick a country where SoundCloud works without limits, for example Germany or the "
                                + "Netherlands, and give the connection a few seconds."),
                new Paragraph("2. Clear the cache.",
                        "Otherwise SoundCloud remembers the old blocks. Open the Arsound settings (" + path
                                + "), the \"Updates and data\" section, the \"Reset SoundCloud data\" button. SoundCloud "
                                + "restarts and downloads your library again, so a connection is needed. Your login, "
                                + "Arsound settings, downloaded and imported tracks and playlist order stay."),
                new Paragraph("3. Shut the door on a Russian IP.",
                        "In the Arsound settings open the \"Network\" section and turn on \"Stay offline on IPs of "
                                + "chosen countries\" (Russia is ticked by default). While the VPN is off or has "
                                + "dropped, Arsound does not contact SoundCloud from a Russian IP, so the account "
                                + "is not marked again."),
        };
    }

    /** Said under the steps: how to bring the hint back after "Don't show again". */
    static String bringBack(boolean russian) {
        return russian
                ? "Вернуть эту подсказку можно в разделе «Сеть» настроек Arsound."
                : "You can bring this hint back in the \"Network\" section of the Arsound settings.";
    }

    static String dontShowAgain(boolean russian) {
        return russian ? "Больше не показывать" : "Don't show again";
    }

    static String badgeDescription(boolean russian) {
        return russian ? "Почему трек недоступен" : "Why the track is not available";
    }

    static String hiddenToast(boolean russian) {
        return russian
                ? "Подсказки скрыты. Вернуть: настройки Arsound → Сеть"
                : "Hints hidden. To bring them back: Arsound settings → Network";
    }

    static String settingTitle(boolean russian) {
        return russian ? "Подсказка у «Недоступно в вашей стране»" : "Hint for \"Not available in your country\"";
    }

    static String settingDescription(boolean russian) {
        return russian
                ? "Рядом с этой пометкой у трека стоит значок «?»: нажмите на него, и Arsound объяснит, почему "
                + "трек недоступен и что делать. Выключено — значков нет."
                : "A \"?\" stands next to that label on a track: tap it and Arsound explains why the track is not "
                + "available and what to do. Off means no icons.";
    }
}
