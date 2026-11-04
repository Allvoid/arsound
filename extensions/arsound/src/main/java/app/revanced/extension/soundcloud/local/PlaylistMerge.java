package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * "Merge playlists" in the menu of a playlist: the tracks of other playlists and albums of the library are
 * added to the end of this one.
 * <p>
 * The playlist whose menu was opened is the target. The user ticks the playlists to take tracks from and puts
 * them in order ({@link MergePicker}). Their tracks follow the target's own, in that order, each playlist in the
 * order it is shown in; a track the target has already, or an earlier playlist brought, is skipped. The playlists
 * taken from are not changed.
 * <p>
 * A playlist of the user is changed the way SoundCloud's own "Add to playlist" does it: its whole track list is
 * saved with {@code editPlaylistTracks}, which SoundCloud then sends to the server; the list is read once more right
 * before, so a track that came to it meanwhile is kept. The tracks of imported files stay on this phone. A playlist
 * of somebody else (or an album) cannot be edited on the server, so its new tracks are added on this phone only,
 * like "Add to playlist locally".
 * <p>
 * A playlist screen lists the tracks on SoundCloud first and the ones added on this phone after them. When that
 * would put the new tracks in another order than the one chosen (files among the tracks, or files the playlist had
 * already), the order is saved as the manual order of the playlist ({@link TrackOrder}).
 */
public final class PlaylistMerge {
    /** SoundCloud does not keep more tracks than this in one playlist. */
    static final int MAX_TRACKS = 500;
    private static final String ROW_TAG = "arsound_playlist_merge_row";

    /** One merge at a time: two would each write a track list that does not know of the other. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private PlaylistMerge() {
    }

    // region Menu

    /** The row of the playlist menu. */
    static void addMenuRow(Dialog dialog, LinearLayout menuItems, String playlistUrn, Object menuData) {
        if (menuItems.findViewWithTag(ROW_TAG) != null) return;
        // Read now: the menu data is the one place that says if SoundCloud offers "Add music" for this playlist.
        boolean menuEditable = hasMenuItem(menuData, "PlaylistMenuItem$AddMusic");
        ViewGroup row = MenuRows.opening(dialog, text("Совместить плейлисты", "Merge playlists"),
                "ic_actions_playlist_add_to_playlist", activity -> open(activity, playlistUrn, menuEditable));
        row.setTag(ROW_TAG);
        MenuRows.append(menuItems, row);
    }

    /** The list of the menu data that holds its rows, or null. The field names are obfuscated, so all lists are looked at. */
    static List<?> menuItems(Object menuData) {
        if (menuData == null) return null;
        try {
            for (Field field : menuData.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(menuData);
                if (!(value instanceof List)) continue;
                for (Object item : (List<?>) value) {
                    if (item != null && item.getClass().getName().contains("PlaylistMenuItem$")) return (List<?>) value;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the playlist menu", ex);
        }
        return null;
    }

    /** Whether a list in the menu data holds an item of this class. The field names are obfuscated, so all lists are looked at. */
    private static boolean hasMenuItem(Object menuData, String className) {
        if (menuData == null) return false;
        try {
            for (Field field : menuData.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(menuData);
                if (!(value instanceof List)) continue;
                for (Object item : (List<?>) value) {
                    if (item != null && item.getClass().getName().contains(className)) return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the playlist menu", ex);
        }
        return false;
    }

    // endregion

    // region Merge

    private static void open(Context activity, String targetUrn, boolean menuEditable) {
        Utils.runOnBackgroundThread(() -> {
            MergeLibrary library = null;
            try {
                library = MergeLibrary.read(targetUrn, menuEditable);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not list the playlists to merge", ex);
            }
            MergeLibrary found = library;
            Utils.runOnMainThread(() -> {
                if (found == null) {
                    Toasts.showLong(activity, text("Библиотека ещё не загружена, попробуйте через минуту",
                            "The library is not loaded yet, try again in a minute"));
                } else if (found.sources.isEmpty()) {
                    Toasts.showLong(activity, text("В библиотеке нет других плейлистов с треками",
                            "There are no other playlists with tracks in the library"));
                } else {
                    new MergePicker(activity, found, sources -> start(activity, found.target, sources)).show();
                }
            });
        });
    }

    private static void start(Context activity, MergeCandidate target, List<MergeCandidate> sources) {
        if (!RUNNING.compareAndSet(false, true)) {
            Toasts.showLong(activity, text("Совмещение уже идёт", "A merge is already running"));
            return;
        }
        MergeProgress progress = new MergeProgress(activity);
        Utils.runOnBackgroundThread(() -> {
            MergeReport report = new MergeReport();
            progress.attach(Thread.currentThread());
            try {
                new MergeJob(target, sources, progress, report).run();
            } catch (Throwable ex) {
                if (progress.cancelled()) {
                    // Cancel woke a read that was waiting for SoundCloud. Cancel and the start of the save exclude each
                    // other, so nothing was saved.
                    report.cancelled = true;
                } else {
                    Logger.printException(() -> "Could not merge the playlists", ex);
                    // The tracks may have been saved before the error: it is not said that nothing changed then.
                    report.error = report.touched
                            ? text("Что-то пошло не так, результат может быть неполным. Откройте плейлист и проверьте.",
                            "Something went wrong, the result may be partial. Open the playlist to check.")
                            : text("Что-то пошло не так. Плейлист не изменён.", "Something went wrong. The playlist was not changed.");
                }
            } finally {
                progress.detach();
                RUNNING.set(false);
            }
            Utils.runOnMainThread(() -> {
                progress.dismiss();
                // Open playlist screens and the library show the tracks and the numbers.
                if (report.added > 0 || report.touched) LocalAdditions.notifyPlaylistChanged(target.urn);
                if (report.cancelled) {
                    Toasts.showLong(activity, text("Совмещение отменено, плейлист не изменён", "Merge cancelled, the playlist was not changed"));
                } else {
                    report.show(activity, target);
                }
            });
        });
    }

    // endregion
}
