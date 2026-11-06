package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The "Imported" playlist.
 * <p>
 * It lives on this phone only ({@link LocalPlaylists}), yet to the library, the playlist screen, playback and
 * shuffle it is a playlist like any other. Its tracks exist only on this device: every imported file is appended
 * when the app reads the playlist.
 * <p>
 * This class is the entry point and holds what the app tells us ({@link #setPlaylistOperations},
 * {@link #setPostsStorage}); the upkeep of the playlist is {@link SavedPlaylistSync}.
 */
@SuppressWarnings("unused")
public final class SavedPlaylist {
    static final String TITLE_RU = "Импортированные";
    static final String TITLE_EN = "Imported";
    static final String PLAYLIST_URN = "saved_playlist_urn";
    /** Extra copies created by older versions after a reinstall. Hidden from lists, then deleted. */
    static final String DUPLICATES = "saved_playlist_duplicates";
    private static final String EXCLUDED = "saved_playlist_excluded";
    /** Not the class lock: it is no use to wait for a network scan to remove a file from the playlist. */
    private static final Object EXCLUDED_LOCK = new Object();
    private static final long CHECK_DELAY_MS = 8_000;
    private static final long POSTS_CHECK_DELAY_MS = 12_000;

    private static volatile Object playlistOperations;
    private static volatile Object postsStorage;
    private static volatile boolean checked;

    private SavedPlaylist() {
    }

    static SharedPreferences preferences() {
        return LocalPrefs.open();
    }

    public static String title() {
        return Lang.text(TITLE_RU, TITLE_EN);
    }

    /** The urn of the playlist, or null if it does not exist or the option is off. */
    public static String getUrn() {
        if (!Settings.isSavedPlaylistEnabled()) return null;
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(PLAYLIST_URN, null);
    }

    public static boolean isSavedPlaylist(Object urn) {
        String saved = getUrn();
        return saved != null && saved.equals(String.valueOf(urn));
    }

    /** SoundCloud's {@code DefaultPlaylistOperations}, or null before the app made it. */
    public static Object getPlaylistOperations() {
        return playlistOperations;
    }

    /** The same, for the code of this package; see also {@link PlaylistOps}. */
    public static Object playlistOperations() {
        return playlistOperations;
    }

    /** SoundCloud's {@code PostsStorage}, or null before the app made it. */
    static Object postsStorage() {
        return postsStorage;
    }

    /** Called from the constructor of {@code DefaultPlaylistOperations}. */
    public static void setPlaylistOperations(Object instance) {
        playlistOperations = instance;
        if (checked) return;
        checked = true;
        LegacyForYou.cancelJob();
        // Give the app time to sign in and start; this never blocks the UI.
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(CHECK_DELAY_MS);
                SavedPlaylistSync.ensureExists();
                LegacyForYou.removePlaylist();
            } catch (IOException ex) {
                // No network or requests blocked by the region guard: tried again on the next start.
                Logger.printInfo(() -> "Saved tracks playlist not checked: " + ex.getMessage());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not prepare the saved tracks playlist", ex);
            }
        });
    }

    /** Injection point: the constructor of {@code PostsStorage}, the stored posts of the user. */
    public static void setPostsStorage(Object instance) {
        boolean first = postsStorage == null;
        postsStorage = instance;
        if (!first) return;
        // After the sign-in, like the check of the saved playlist.
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(POSTS_CHECK_DELAY_MS);
                SavedPlaylistSync.forgetDeletedCopies();
            } catch (InterruptedException ignored) {
            }
        });
    }

    /**
     * Removes the saved playlist from "my playlists" lists while the user hides it.
     *
     * @param observable {@code Observable<List<PlaylistItem>>}.
     */
    public static Object hideFromLists(Object observable) {
        try {
            return Rx.mapObservable(observable, value -> {
                SharedPreferences preferences = preferences();
                String saved = preferences == null ? null : preferences.getString(PLAYLIST_URN, null);
                Set<String> duplicates = preferences == null ? Collections.emptySet()
                        : preferences.getStringSet(DUPLICATES, Collections.emptySet());
                // Hidden by the user, or the whole option is off: the empty server playlist stays out of sight.
                boolean hideSaved = saved != null && !(Settings.isSavedPlaylistEnabled() && !Settings.isSavedPlaylistHidden());
                if (!hideSaved && duplicates.isEmpty()) return value;
                List<Object> items = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    try {
                        String urn = Urns.textOf(item);
                        if ((hideSaved && saved.equals(urn)) || duplicates.contains(urn)) continue;
                    } catch (Exception ignored) {
                    }
                    items.add(item);
                }
                return items;
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not hide the saved tracks playlist", ex);
            return observable;
        }
    }

    /**
     * A playlist with no tracks on the server makes SoundCloud load it from the network every time, and
     * show "No internet connection" offline. The saved playlist, and any playlist whose tracks were all
     * added on this device, are complete in storage, so the stored playlist is used as is.
     */
    public static boolean useStoredPlaylist(Object urn, Object response) {
        if (response == null || !isFound(response)) return false;
        if (isSavedPlaylist(urn) || LocalPlaylists.isLocal(urn)) return true;
        try {
            Object playlist = response.getClass().getMethod("getItem").invoke(response);
            List<?> tracks = (List<?>) playlist.getClass().getMethod("getTracks").invoke(playlist);
            boolean localOnly = tracks.isEmpty() && !LocalAdditions.getEntries(String.valueOf(urn)).isEmpty();
            if (localOnly) Logger.printInfo(() -> "Playlist with only local tracks shown from storage: " + urn);
            return localOnly;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check the stored playlist", ex);
            return false;
        }
    }

    /** {@code SingleItemResponse.Found} comes as its subclasses, such as {@code Found.Fresh}. */
    private static boolean isFound(Object response) {
        for (Class<?> type = response.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith("SingleItemResponse$Found")) return true;
        }
        return false;
    }

    /** Removes a track from the saved playlist without deleting the file or the download. */
    public static void exclude(String entry) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        synchronized (EXCLUDED_LOCK) {
            Set<String> excluded = new HashSet<>(preferences.getStringSet(EXCLUDED, new HashSet<>()));
            excluded.add(entry);
            preferences.edit().putStringSet(EXCLUDED, excluded).apply();
        }
    }

    /**
     * Forgets the removal of a file from the saved playlist, once the file is deleted: the next file with
     * the same name gets the same path and must show up in the playlist.
     */
    public static void unexclude(String entry) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        synchronized (EXCLUDED_LOCK) {
            Set<String> excluded = new HashSet<>(preferences.getStringSet(EXCLUDED, new HashSet<>()));
            if (excluded.remove(entry)) preferences.edit().putStringSet(EXCLUDED, excluded).apply();
        }
    }

    /** Imported files, newest first. Tracks downloaded by Arsound are not part of this playlist. */
    public static List<String> getEntries() {
        SharedPreferences preferences = preferences();
        Set<String> excluded = preferences == null ? new HashSet<>()
                : preferences.getStringSet(EXCLUDED, new HashSet<>());
        List<String> entries = new ArrayList<>();
        Context context = Utils.getContext();
        if (context != null) {
            for (File file : LocalMusic.getFiles(context)) {
                String entry = Entry.ofFile(file).stored();
                if (!excluded.contains(entry)) entries.add(entry);
            }
        }
        return entries;
    }
}
