package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.soundcloud.local.ImportActivity;
import app.revanced.extension.soundcloud.local.ImportedLikes;
import app.revanced.extension.soundcloud.local.PlaylistOrder;
import app.revanced.extension.soundcloud.local.TrackOrder;
import app.revanced.extension.soundcloud.local.WatchFolder;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/** The phone's own music: importing files, the "Imported" playlist, the order of playlists and tracks. */
final class MusicScreen extends SectionScreen {
    static final String ID = "music";

    /** The line under the watched folder's row, shown again when the screen comes back from the folder picker. */
    private TextView folderName;

    MusicScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Своя музыка", "Your music");
    }

    @Override
    String description() {
        return text("Импорт файлов, плейлист «Импортированные», свой порядок", "Import files, \"Imported\" playlist, custom order");
    }

    @Override
    String iconKey() {
        return "music";
    }

    @Override
    String summary() {
        return text("Файлы с телефона: ", "Files from the phone: ")
                + (WatchFolder.isEnabled() ? text("слежу за папкой", "watching a folder") : text("импорт вручную", "manual import"));
    }

    @Override
    void fill(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Файлы", "Files")));
        list.addView(ui.createActionRow(
                text("Импортированные файлы", "Imported files"),
                text("Добавить аудиофайлы с телефона и посмотреть уже добавленные.",
                        "Add audio files from the phone and see the ones already added."),
                v -> activity.startActivity(ui.screenIntent(LocalMusicScreen.ID))
        ).icon("import").chevron());
        addWatchFolderOptions(list);

        list.addView(ui.createSubHeading(text("Из других сервисов", "From other services")));
        list.addView(ui.createActionRow(
                text("Перенос из Яндекс Музыки", "Transfer from Yandex Music"),
                text("Плейлист, альбом или лайки из Яндекс Музыки становятся приватным плейлистом SoundCloud: "
                                + "Arsound находит каждый трек в SoundCloud. По ссылке или из своего аккаунта Яндекса.",
                        "A playlist, an album or likes from Yandex Music become a private SoundCloud playlist: "
                                + "Arsound finds each track on SoundCloud. By link or from your Yandex account."),
                v -> activity.startActivity(ui.screenIntent(TransferScreen.ID))
        ).icon("import").chevron());

        list.addView(ui.createSubHeading(text("В библиотеке", "In the library")));
        SubRows savedOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Плейлист «Импортированные»", "\"Imported\" playlist"),
                text("Приватный плейлист в библиотеке со всеми импортированными файлами. Треки в нём есть только "
                                + "на этом телефоне. Если удалить плейлист, при следующем запуске он появится снова. "
                                + "Применится после перезапуска.",
                        "A private playlist in the library with all imported files. Its tracks exist only on this phone. "
                                + "If you delete it, it comes back on the next start. Applies after a restart."),
                BoolSettings.SAVED_PLAYLIST,
                savedOptions
        ).icon("music").brief(text("Приватный плейлист со всеми импортированными файлами.",
                "A private playlist with all imported files.")));
        savedOptions.addView(ui.createToggleRow(
                text("Скрыть этот плейлист", "Hide this playlist"),
                text("Плейлист пропадёт из библиотеки, но импортированные треки останутся.",
                        "The playlist disappears from the library, imported tracks stay."),
                BoolSettings.SAVED_PLAYLIST_HIDDEN
        ));
        list.addView(savedOptions);
        SubRows likeOptions = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Импортированные в «Ваших лайках»", "Imported tracks in \"Your likes\""),
                text("Лайкнутые импортированные файлы показываются среди ваших лайков: новые сверху, сердечко "
                                + "закрашено, играют оттуда же. Нажатие на сердечко убирает файл из лайков (сам файл "
                                + "остаётся в «Импортированных»), повторное возвращает; можно лайкнуть и файл, который "
                                + "не лайкнулся при импорте. Только на этом телефоне, на сервер ничего не уходит. "
                                + "Поиск по лайкам находит файлы по имени. Выключено — файлов нет среди лайков вовсе.",
                        "Liked imported files show up among your likes: the newest on top, the heart filled, and they "
                                + "play from there. Tapping the heart removes a file from the likes (the file itself "
                                + "stays in Imported), tapping again brings it back; a file that was not liked on import "
                                + "can be liked too. Only on this phone; nothing is sent to the server. Searching the "
                                + "likes finds files by name. Off: no files among the likes at all."),
                BoolSettings.IMPORTED_IN_LIKES,
                likeOptions,
                (button, checked) -> {
                    // The likes screen shows the change the next time it loads; an open one is redrawn now.
                    ImportedLikes.refresh();
                }
        ).icon("widget").brief(text("Лайкнутые импортированные файлы показываются среди ваших лайков.",
                "Liked imported files show up among your likes.")));
        likeOptions.addView(ui.createToggleRow(
                text("Лайкать импортированные при импорте", "Like imported tracks when importing"),
                text("Новый импортированный трек сразу лайкается: сердечко закрашено в интерфейсе SoundCloud, он в "
                                + "«Ваших лайках». Снимете лайк — трек пропадёт из лайков и останется только в "
                                + "«Импортированных». Выключено — новые треки не лайкаются, но любой можно лайкнуть "
                                + "самому сердечком. Уже импортированные треки это не меняет: ни ваши лайки, ни ваши "
                                + "снятые лайки.",
                        "A track you import is liked at once: the heart is filled in SoundCloud's interface and it is "
                                + "among \"Your likes\". Take the like away and it leaves the likes and stays only in "
                                + "Imported. Off: new tracks are not liked, but you can like any of them with the heart. "
                                + "Files already imported are left as they are: neither your likes nor the likes you "
                                + "took away change."),
                BoolSettings.LIKE_ON_IMPORT,
                (button, checked) -> {
                    if (checked) offerToLikeImported();
                }
        ));
        list.addView(likeOptions);

        list.addView(ui.createSubHeading(text("Порядок", "Order")));
        list.addView(ui.createToggleRow(
                text("Свой порядок плейлистов и треков", "Custom playlist and track order"),
                text("Зажмите плейлист в «Библиотека → Плейлисты» или трек внутри плейлиста и перетащите. "
                                + "Чтобы закончить, коснитесь списка. Порядок хранится на телефоне, треки играют в нём же.",
                        "Press and hold a playlist in Library → Playlists, or a track inside a playlist, and drag it. "
                                + "Tap the list to finish. The order is kept on this phone and playback follows it."),
                BoolSettings.PLAYLIST_ORDER
        ).icon("order").brief(text("Зажмите плейлист или трек и перетащите.", "Press and hold a playlist or track and drag.")));
        list.addView(ui.createActionRow(
                text("Сбросить порядок", "Reset order"),
                text("Вернуть порядок SoundCloud для плейлистов и треков.",
                        "Go back to SoundCloud's order of playlists and tracks."),
                v -> {
                    PlaylistOrder.reset();
                    TrackOrder.resetAll();
                    Toast.makeText(activity, text("Порядок сброшен", "Order reset"), Toast.LENGTH_SHORT).show();
                }
        ).icon("reset"));
    }

    private void addWatchFolderOptions(LinearLayout list) {
        SubRows options = ui.createSubRows();
        list.addView(ui.createToggleRow(
                text("Следить за папкой", "Watch a folder"),
                text("Новые аудиофайлы из выбранной папки на телефоне сами импортируются, когда вы открываете SoundCloud. "
                                + "Удаление файла из папки импортированный трек не трогает.",
                        "New audio files in the chosen phone folder are imported by themselves when you open SoundCloud. "
                                + "Removing a file from the folder does not remove the imported track."),
                WatchFolder.isEnabled(),
                (button, checked) -> {
                    if (checked && WatchFolder.getFolder() == null) {
                        ImportActivity.pickWatchFolder(activity);
                    }
                    WatchFolder.setEnabled(checked);
                    options.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
        ).icon("folder").brief(text("Новые файлы из выбранной папки импортируются сами.",
                "New files in the chosen folder are imported by themselves.")));
        options.setVisibility(WatchFolder.isEnabled() ? View.VISIBLE : View.GONE);
        list.addView(options);
        ActionRow folderRow = ui.createActionRow(text("Папка", "Folder"), "",
                v -> ImportActivity.pickWatchFolder(activity));
        options.addView(folderRow);
        options.addView(ui.createActionRow(text("Проверить сейчас", "Check now"),
                text("Импортировать новые файлы из папки, не дожидаясь следующего запуска.",
                        "Import new files from the folder without waiting for the next start."),
                v -> WatchFolder.check(activity.getApplicationContext(), true)));
        // The folder is picked in another screen: the name is shown again when this one comes back.
        folderName = folderRow.description();
        updateFolderName();
    }

    private void updateFolderName() {
        if (folderName == null) return;
        String name = WatchFolder.folderName();
        folderName.setText(name != null ? name : text("Не выбрана — нажмите, чтобы выбрать", "Not chosen, tap to choose"));
    }

    @Override
    void onResume() {
        updateFolderName();
    }

    /**
     * "Like what I import" was switched on: files imported while it was off stay as they are, unless the user wants them
     * liked too. The ones the user took the like away from are never liked by this.
     */
    private void offerToLikeImported() {
        int waiting = ImportedLikes.undecidedCount();
        if (waiting == 0) return;
        new ArsoundDialog(activity)
                .setTitle(text("Лайкнуть и уже импортированные?", "Like the ones imported earlier too?"))
                .setMessage(text("Есть импортированные треки, которые не лайкнуты (их " + waiting + "). Лайкнуть их "
                                + "тоже? Треки, у которых вы сами сняли лайк, не тронутся.",
                        "Some imported tracks are not liked (" + waiting + "). Like them too? Tracks you took the "
                                + "like away from stay as they are."))
                .setNegativeButton(text("Нет, только новые", "No, new ones only"), null)
                .setPositiveButton(text("Лайкнуть", "Like them"), (dialog, which) -> {
                    int liked = ImportedLikes.likeUndecided();
                    Toast.makeText(activity, text("Лайкнуто: " + liked, "Liked: " + liked), Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
