package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.MixWithOtherApps;
import app.revanced.extension.soundcloud.player.NextTrackPreload;
import app.revanced.extension.soundcloud.player.PlayerComments;
import app.revanced.extension.soundcloud.player.VivoMusicCard;

/**
 * Transitions between tracks, loading the next track ahead, the comments in the player, retrying, mixing with other
 * apps, the lyrics, playing offline.
 */
final class PlaybackScreen extends SectionScreen {
    static final String ID = "playback";
    /** The rows of the lyrics recognition built last; null until the screen is built. */
    private LyricsAiRows aiRows;

    PlaybackScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String description() {
        return text("Плавный переход между треками, загрузка следующего трека, комментарии в плеере, тексты песен, офлайн",
                "Smooth transitions, loading the next track, comments in the player, lyrics, offline");
    }

    @Override
    String title() {
        return text("Воспроизведение", "Playback");
    }

    @Override
    String iconKey() {
        return "playback";
    }

    @Override
    String keywords() {
        return text("плеер музыка играть тексты песен офлайн переход затухание кроссфейд комментарии предзагрузка",
                "player music play lyrics offline transition fade crossfade comments preload");
    }

    @Override
    String summary() {
        return text("Плавный переход: ", "Smooth transition: ") + (BoolSettings.CROSSFADE.get() ? text("вкл", "on") : text("выкл", "off"))
                + text(" · тексты: ", " · lyrics: ") + (BoolSettings.LYRICS_ENABLED.get() ? text("вкл", "on") : text("выкл", "off"));
    }

    @Override
    void fill(LinearLayout list) {
        addTransitionRows(list);
        addPlayerRows(list);

        list.addView(ui.createSubHeading(text("Воспроизведение", "Playback")));
        list.addView(ui.createToggleRow(
                text("Повторять при обрыве связи", "Retry on dropped connection"),
                text("Если трек из сети оборвался, плеер сам попробует ещё до трёх раз вместо ошибки "
                                + "«Track cannot be streamed».",
                        "If a streamed track stops, the player tries up to three more times instead of showing "
                                + "\"Track cannot be streamed\"."),
                BoolSettings.PLAYBACK_RETRY
        ).icon("retry"));
        list.addView(ui.createToggleRow(
                text("Играть вместе с другими приложениями", "Play along with other apps"),
                text("Музыка не встаёт на паузу и не становится тише, когда звук включает другое приложение: видео, "
                                + "голосовое сообщение, игра или другой плеер. Звучат оба. Учтите: музыка не "
                                + "остановится и во время записи голосового или видео. При звонке пауза остаётся, "
                                + "после звонка музыка продолжится.",
                        "Music does not pause or get quieter when another app plays sound: a video, a voice message, "
                                + "a game or another player. Both play. Note that music also keeps playing while you "
                                + "record a voice message or a video. Calls still pause it, and it resumes after the call."),
                BoolSettings.MIX_WITH_OTHER_APPS,
                (button, checked) -> MixWithOtherApps.onSettingChanged(checked)
        ).icon("mix").brief(text("Музыка не встаёт на паузу, когда звук включает другое приложение.",
                "Music does not pause when another app plays sound.")));

        addLyricsOptions(list);

        list.addView(ui.createSubHeading(text("Офлайн", "Offline")));
        TextView note = ui.createText("Body.Secondary", text(
                "Скачанные треки всегда играют из файла на телефоне — сразу, без интернета и без трафика.",
                "Downloaded tracks always play from the file on the phone: right away, without a connection or data."));
        note.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        list.addView(note);
        list.addView(ui.createToggleRow(
                text("Сохранять плейлисты заранее", "Save playlists ahead"),
                text("В фоне сохраняет списки треков всех плейлистов и альбомов из библиотеки: названия, исполнителей, "
                                + "длительность, и обложки самих плейлистов. Без музыки, места почти не занимает. Нужно, "
                                + "чтобы плейлисты открывались без интернета и с обложками.",
                        "Saves the track lists of every playlist and album in the library in the background: titles, "
                                + "artists, durations, and the covers of the playlists themselves. No audio, takes almost "
                                + "no space. Lets playlists open offline, with their covers."),
                BoolSettings.PLAYLIST_PRELOAD
        ).icon("offline").brief(text("Списки треков и обложки плейлистов — для работы без интернета.",
                "Track lists and playlist covers, for working offline.")));
        list.addView(ui.createToggleRow(
                text("Открывать плейлисты сразу", "Open playlists right away"),
                text("Плейлист показывается из памяти телефона, не дожидаясь SoundCloud, а обновляется в фоне.",
                        "A playlist is shown from the phone without waiting for SoundCloud and refreshes in the background."),
                BoolSettings.OFFLINE_FIRST
        ).icon("playback"));

        addVivoMusicCardRow(list);
    }

    /** How one track goes over into the next: the fades, and the next track loaded ahead. */
    private void addTransitionRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Между треками", "Between tracks")));
        SubRows fadeOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Плавный переход между треками", "Smooth transition between tracks"),
                text("Конец трека плавно затихает, а следующий трек начинается тихо и плавно набирает громкость. "
                                + "Срабатывает, когда трек доиграл сам; трек, который вы включили или переключили сами, "
                                + "начинается сразу на полной громкости. Меняется громкость плеера, а не телефона.",
                        "The end of a track fades out, and the next track starts quietly and fades in. It works when a "
                                + "track played to its end; a track you picked or skipped to starts at full volume at "
                                + "once. The player's volume changes, not the phone's."),
                BoolSettings.CROSSFADE,
                fadeOptions
        ).icon("volume").brief(text("Трек затихает в конце, следующий плавно нарастает.",
                "A track fades out at its end, the next one fades in.")));
        fadeOptions.addView(ui.createChoiceRow(text("Длительность затухания", "Fade length"), null,
                Settings.CROSSFADE_SECONDS, 6, new long[]{2, 4, 6, 8, 10, 12},
                new String[]{text("2 с", "2 s"), text("4 с", "4 s"), text("6 с", "6 s"), text("8 с", "8 s"),
                        text("10 с", "10 s"), text("12 с", "12 s")},
                null));
        list.addView(fadeOptions);
        list.addView(ui.createChoiceRow(text("Загружать следующий трек заранее", "Load the next track ahead"),
                text("Пока играет трек, следующий из облака скачивается целиком во временную папку, поэтому "
                                + "начинается сразу и не прерывается на слабой сети. Скачанные и свои треки "
                                + "уже на телефоне, их это не касается. По мобильной сети следующий трек грузится "
                                + "за полторы минуты до конца текущего, чтобы не тратить трафик на пропущенные треки. "
                                + "Временные копии удаляются сами и не видны среди скачанных.",
                        "While a track plays, the next one from the cloud is fetched whole into a temporary folder, "
                                + "so it starts at once and does not stop on a weak network. Downloaded and imported "
                                + "tracks are already on the phone and are left alone. On mobile data the next track "
                                + "is fetched a minute and a half before the current one ends, so skipped tracks cost "
                                + "no data. The temporary copies delete themselves and are not shown as downloads."),
                Settings.PRELOAD_NEXT, NextTrackPreload.ALWAYS,
                new long[]{NextTrackPreload.OFF, NextTrackPreload.UNMETERED, NextTrackPreload.ALWAYS},
                new String[]{text("Никогда", "Never"), text("Только по Wi-Fi", "Wi-Fi only"),
                        text("Wi-Fi и мобильная сеть", "Wi-Fi and mobile data")},
                () -> {
                    // Switched off: the copies made ahead are not needed any more and go now.
                    if (NextTrackPreload.mode() != NextTrackPreload.OFF) return;
                    android.content.Context context = activity.getApplicationContext();
                    Utils.runOnBackgroundThread(() -> app.revanced.extension.soundcloud.download.PrefetchedTracks
                            .keepOnly(context, java.util.Collections.emptyList()));
                }).icon("offline").brief(text("Следующий трек из облака скачивается, пока играет текущий.",
                "The next track from the cloud is fetched while the current one plays.")));
    }

    /** What the full screen player shows besides the track. */
    private void addPlayerRows(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Плеер", "Player")));
        list.addView(ui.createToggleRow(
                text("Скрыть всплывающие комментарии", "Hide pop-up comments"),
                text("Комментарии слушателей больше не всплывают поверх обложки, пока играет трек, и не отмечаются "
                                + "на волне. Писать и читать комментарии по-прежнему можно: кнопка комментариев под "
                                + "плеером остаётся.",
                        "Listeners' comments no longer pop up over the artwork while a track plays and are not marked "
                                + "on the waveform. You can still read and write comments: the comments button under "
                                + "the player stays."),
                BoolSettings.HIDE_PLAYER_COMMENTS,
                (button, checked) -> PlayerComments.onSettingChanged(checked)
        ).icon("log").brief(text("Комментарии не всплывают над обложкой во время трека.",
                "Comments do not pop up over the artwork while a track plays.")));
    }

    /** The lyrics button of the player. The cache of the lyrics is cleared on the storage screen. */
    private void addLyricsOptions(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Тексты песен", "Lyrics")));
        list.addView(ui.createToggleRow(
                text("Кнопка текста в плеере", "Lyrics button in the player"),
                text("В большом плеере под кнопкой подписки появляется кнопка с текстом песни. Пока её не нажали, "
                                + "ничего не ищется. После нажатия название и исполнитель играющего трека отправляются "
                                + "на lrclib.net, найденный текст сохраняется на телефоне.",
                        "A lyrics button appears under the follow button in the full-screen player. Nothing is looked "
                                + "up until you tap it. After that the title and the artist of the playing track are sent "
                                + "to lrclib.net, and the text it finds is kept on the phone."),
                BoolSettings.LYRICS_ENABLED
        ).icon("lyrics").brief(text("Кнопка с текстом песни в большом плеере.", "A lyrics button in the full-screen player.")));
        addLyricsAiRows(list);
    }

    /**
     * The rows of the AI lyrics transcription (recognising the words of a song that has no text), in the "Lyrics"
     * card right after the lyrics button. {@link LyricsAiRows} hides them on phones the model cannot run on.
     */
    void addLyricsAiRows(LinearLayout list) {
        // The search builds the rows once more off screen: the older ones must let go of the download first.
        if (aiRows != null) aiRows.detach();
        aiRows = new LyricsAiRows(ui);
        aiRows.addTo(list);
    }

    @Override
    void onDestroy() {
        if (aiRows != null) aiRows.detach();
    }

    /** Only on vivo phones: whether their control centre player sees Arsound, and how to add it. */
    private void addVivoMusicCardRow(LinearLayout list) {
        if (!VivoMusicCard.isSupported(activity)) return;
        list.addView(ui.createSubHeading("vivo"));
        TextView[] description = new TextView[1];
        ActionRow row = ui.createActionRow(text("Плеер в шторке vivo", "The vivo control centre player"), "",
                v -> VivoMusicCard.showHelp(activity, () ->
                        description[0].setText(VivoMusicCard.describe(VivoMusicCard.state(activity)))));
        row.icon("player").chevron();
        description[0] = row.description();
        description[0].setText(VivoMusicCard.describe(VivoMusicCard.state(activity)));
        list.addView(row);
    }
}
