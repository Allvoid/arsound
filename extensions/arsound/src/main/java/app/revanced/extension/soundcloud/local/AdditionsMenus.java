package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/** The menu rows and the pickers for tracks and covers that exist only on this phone. */
final class AdditionsMenus {
    private static final String TRACK_ROW_TAG = "arsound_local_add_row";
    private static final String PLAYLIST_ROW_TAG = "arsound_local_additions_row";
    private static final String COVER_ROW_TAG = "arsound_cover_row";
    private static final String REMOVE_COVER_ROW_TAG = "arsound_remove_cover_row";

    private AdditionsMenus() {
    }

    /** "Add to playlist locally" in the menu of a track. */
    static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        try {
            MenuRows.TrackMenu menu = MenuRows.TrackMenu.find(dialog);
            if (menu == null) return;
            menu.removeTagged(TRACK_ROW_TAG);

            String entry = String.valueOf(trackUrn);
            ViewGroup row = MenuRows.opening(dialog, text("Добавить в плейлист локально", "Add to playlist locally"),
                    "ic_actions_playlist_add_to_playlist", activity -> pickPlaylist(activity, entry));
            row.setTag(TRACK_ROW_TAG);
            menu.addBelowItems(row);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local playlist row", ex);
        }
    }

    /** "Set cover" in the menu of an imported file. */
    static void addTrackCoverRow(Dialog dialog, File audio) {
        try {
            MenuRows.TrackMenu menu = MenuRows.TrackMenu.find(dialog);
            if (menu == null) return;
            menu.removeTagged(COVER_ROW_TAG);

            boolean hasCover = LocalCovers.hasUserCover(audio);
            ViewGroup row = MenuRows.opening(dialog, hasCover ? text("Сменить обложку", "Change cover") : text("Задать обложку", "Set cover"),
                    "ic_actions_image", activity -> ImportActivity.pickTrackCover(activity, audio));
            row.setTag(COVER_ROW_TAG);
            menu.addBelowItems(row);
            menu.removeTagged(REMOVE_COVER_ROW_TAG);
            if (!LocalCovers.hasUserCover(audio)) return;
            ViewGroup removeRow = MenuRows.running(dialog, text("Убрать свою обложку", "Remove own cover"),
                    "ic_actions_delete", () -> Utils.runOnBackgroundThread(() -> {
                        LocalCovers.removeUserCover(audio);
                        Utils.runOnMainThread(() -> PlaylistChanges.trackChanged(audio));
                    }));
            removeRow.setTag(REMOVE_COVER_ROW_TAG);
            menu.addAfter(row, removeRow);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the cover row", ex);
        }
    }

    /**
     * "Set cover" in the menu of a playlist without SoundCloud tracks: its artwork would stay empty,
     * so a picture from the gallery is shown instead, on this device only.
     */
    private static void addPlaylistCoverRows(Dialog dialog, LinearLayout menuItems, String playlistUrn) {
        boolean hasCover = LocalCovers.hasPlaylistCover(playlistUrn);
        if (!hasCover && !PlaylistTracks.hasOnlyImportedTracks(playlistUrn)) return;
        MenuRows.append(menuItems, MenuRows.opening(dialog,
                hasCover ? text("Сменить обложку", "Change cover") : text("Задать обложку", "Set cover"),
                "ic_actions_image", activity -> ImportActivity.pickPlaylistCover(activity, playlistUrn)));
        if (!hasCover) return;
        MenuRows.append(menuItems, MenuRows.running(dialog, text("Убрать свою обложку", "Remove own cover"),
                "ic_actions_delete", () -> Utils.runOnBackgroundThread(() -> {
                    LocalCovers.setPlaylistCover(playlistUrn, null);
                    Utils.runOnMainThread(() -> {
                        PlaylistChanges.playlistChanged(playlistUrn);
                        PlaylistHeader.redraw(playlistUrn);
                    });
                })));
    }

    /** @param menuData The data SoundCloud built the menu from, to see whether the playlist can be edited. */
    static void addPlaylistMenuRows(Dialog dialog, String playlistUrn, Object menuData) {
        try {
            LinearLayout menuItems = MenuRows.playlistMenuItems(dialog);
            if (menuItems == null || menuItems.findViewWithTag(PLAYLIST_ROW_TAG) != null) return;

            int count = AdditionsStore.entries(playlistUrn).size();
            ViewGroup row = MenuRows.opening(dialog,
                    text("Локальные треки", "Local tracks") + (count > 0 ? " (" + count + ")" : ""),
                    "ic_actions_playlist_add_to_playlist", activity -> manageAdditions(activity, playlistUrn));
            row.setTag(PLAYLIST_ROW_TAG);
            MenuRows.append(menuItems, row);

            MenuRows.append(menuItems, MenuRows.opening(dialog, text("Импортировать музыку сюда", "Import music here"),
                    "ic_actions_upload", activity -> ImportActivity.start(activity, playlistUrn)));

            // The "Imported" playlist lists the files by itself: nothing is merged into it.
            if (!SavedPlaylist.isSavedPlaylist(playlistUrn)) PlaylistMerge.addMenuRow(dialog, menuItems, playlistUrn, menuData);

            addPlaylistCoverRows(dialog, menuItems, playlistUrn);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local additions row", ex);
        }
    }

    /** Lets the user pick a playlist: own playlists and recently opened playlists and albums. */
    static void pickPlaylist(Context context, String entry) {
        List<String[]> targets = AdditionsStore.recentPlaylists();
        String saved = SavedPlaylist.getUrn();
        if (saved != null) targets.removeIf(item -> item[0].equals(saved));
        if (targets.isEmpty()) {
            Toast.makeText(context, text("Откройте нужный плейлист или альбом — он появится в списке.",
                    "Open the playlist or album first, then it appears in the list."), Toast.LENGTH_LONG).show();
            return;
        }

        List<LocalSheet.Item> items = LocalSheet.items();
        for (String[] target : targets) {
            items.add(new LocalSheet.Item(target[1], "ic_actions_playlist_add_to_playlist", () -> {
                boolean added = AdditionsStore.add(target[0], entry);
                TrackTitles.remember(entry);
                PlaylistChanges.playlistChanged(target[0]);
                Toast.makeText(context, added
                                ? text("Добавлено в «" + target[1] + "» только на этом телефоне",
                                "Added to \"" + target[1] + "\" on this phone only")
                                : text("Уже в этом плейлисте", "Already in this playlist"),
                        Toast.LENGTH_SHORT).show();
            }));
        }
        LocalSheet.show(context, text("Добавить локально", "Add locally"),
                text("Трек будет виден и играть только на этом телефоне", "The track is visible and plays only on this phone"),
                items);
    }

    /** "Local tracks" of a playlist: what was added on this phone, with removal and adding own files. */
    private static void manageAdditions(Context context, String playlistUrn) {
        Utils.runOnBackgroundThread(() -> {
            List<String> entries = AdditionsStore.entries(playlistUrn);
            List<LocalMusic.Track> imported = LocalMusic.getTracks(context);
            TrackTitles.rememberAll(entries);
            // The names are read from the cached titles once for the whole list: reading them again for every entry
            // would parse the whole map each time, which freezes the screen when a merge brought hundreds of tracks.
            TrackTitles.Snapshot titles = TrackTitles.snapshot();
            List<String> labels = new ArrayList<>(entries.size());
            for (String entry : entries) labels.add(Entry.parse(entry).label(imported, titles));
            Utils.runOnMainThread(() -> {
                List<LocalSheet.Item> items = LocalSheet.items();
                items.add(new LocalSheet.Item(text("Добавить свой файл", "Add my file"), "ic_actions_playlist_add_to_playlist",
                        () -> pickImportedFile(context, playlistUrn, imported)));
                for (int i = 0; i < entries.size(); i++) {
                    String entry = entries.get(i);
                    String label = labels.get(i);
                    items.add(new LocalSheet.Item(label, "ic_actions_playlist_remove_from_playlist", () -> {
                        AdditionsStore.remove(playlistUrn, entry);
                        PlaylistChanges.playlistChanged(playlistUrn);
                        Toast.makeText(context, text("Убрано: " + label, "Removed: " + label), Toast.LENGTH_SHORT).show();
                    }));
                }
                LocalSheet.show(context, text("Локальные треки", "Local tracks"),
                        entries.isEmpty()
                                ? text("Здесь пока нет треков, добавленных на этом телефоне. Трек SoundCloud добавляется "
                                        + "из его меню: «Добавить в плейлист локально».",
                                "No tracks added on this phone yet. Add a SoundCloud track from its menu.")
                                : text("Нажмите на трек, чтобы убрать его из плейлиста", "Tap a track to remove it"),
                        items);
            });
        });
    }

    private static void pickImportedFile(Context context, String playlistUrn, List<LocalMusic.Track> imported) {
        if (imported.isEmpty()) {
            Toast.makeText(context, text("Сначала импортируйте файлы: Arsound → Импортированные файлы",
                    "Import files first: Arsound → Imported files"), Toast.LENGTH_LONG).show();
            return;
        }
        List<LocalSheet.Item> items = LocalSheet.items();
        for (LocalMusic.Track track : imported) {
            items.add(new LocalSheet.Item(track.title, "ic_actions_playlist_add_to_playlist", () -> {
                AdditionsStore.add(playlistUrn, Entry.ofFile(track.file).stored());
                PlaylistChanges.playlistChanged(playlistUrn);
                Toast.makeText(context, text("Добавлено: " + track.title, "Added: " + track.title), Toast.LENGTH_SHORT).show();
            }));
        }
        LocalSheet.show(context, text("Импортированные файлы", "Imported files"), null, items);
    }
}
