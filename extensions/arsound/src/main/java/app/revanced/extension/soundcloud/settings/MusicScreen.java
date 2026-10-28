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
        list.addView(ui.createToggleRow(
                text("Импортированные в «Ваших лайках»", "Imported tracks in \"Your likes\""),
                text("Импортированные файлы показываются среди ваших лайков, будто вы их лайкнули: новые сверху, "
                                + "сердечко закрашено, играют оттуда же. Нажатие на сердечко убирает файл из лайков "
                                + "(сам файл остаётся), повторное возвращает. Только на этом телефоне, на сервер "
                                + "ничего не уходит. Поиск по лайкам находит файлы по имени.",
                        "Imported files show up among your likes as if you liked them: the newest on top, the heart "
                                + "filled, and they play from there. Tapping the heart removes a file from the likes "
                                + "(the file itself stays), tapping again brings it back. Only on this phone; nothing "
                                + "is sent to the server. Searching the likes finds files by name."),
                BoolSettings.IMPORTED_IN_LIKES,
                (button, checked) -> {
                    // The likes screen shows the change the next time it loads; an open one is redrawn now.
                    ImportedLikes.refresh();
                }
        ).icon("widget").brief(text("Импортированные файлы показываются среди ваших лайков.",
                "Imported files show up among your likes.")));

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
}
