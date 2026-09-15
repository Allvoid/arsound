package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The "Downloaded and imported" playlist.
 * <p>
 * It is a regular, empty, private SoundCloud playlist, so the library, the playlist screen,
 * playback and shuffle work natively. Its tracks exist only on this device: every track
 * downloaded by Arsound and every imported file is appended when the app reads the playlist.
 * If the playlist is deleted, it is created again on the next start while the option is on.
 */
@SuppressWarnings("unused")
public final class SavedPlaylist {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String PLAYLIST_URN = "saved_playlist_urn";

    private static volatile Object playlistOperations;
    private static volatile boolean checked;

    private SavedPlaylist() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static String title() {
        return "ru".equals(Locale.getDefault().getLanguage()) ? "Скачанные и импортированные" : "Downloaded and imported";
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

    /** Called from the constructor of {@code DefaultPlaylistOperations}. */
    public static void setPlaylistOperations(Object instance) {
        playlistOperations = instance;
        if (checked) return;
        checked = true;
        // Give the app time to sign in and start; this never blocks the UI.
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(8_000);
                ensureExists();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not prepare the saved tracks playlist", ex);
            }
        });
    }

    private static void ensureExists() throws Exception {
        SharedPreferences preferences = preferences();
        Logger.printInfo(() -> "Checking saved playlist, enabled: " + Settings.isSavedPlaylistEnabled()
                + ", stored: " + (preferences == null ? null : preferences.getString(PLAYLIST_URN, null)));
        if (!Settings.isSavedPlaylistEnabled() || preferences == null) return;

        String urn = preferences.getString(PLAYLIST_URN, null);
        if (urn != null) {
            String id = urn.substring(urn.lastIndexOf(':') + 1);
            String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + id);
            // Only a definite "not found" means it was deleted; network errors keep the playlist.
            if (!"404".equals(response[0])) return;
            Logger.printInfo(() -> "Saved tracks playlist was deleted, creating it again");
        }
        create(preferences);
    }

    private static void create(SharedPreferences preferences) throws Exception {
        Object operations = playlistOperations;
        if (operations == null) return;

        Object single = operations.getClass()
                .getMethod("createNewPlaylist", String.class, boolean.class, List.class)
                .invoke(operations, title(), false, Collections.emptyList());
        Object result = Rx.blockingFirst(single, 30, java.util.concurrent.TimeUnit.SECONDS);
        if (result == null || !result.getClass().getName().endsWith("PlaylistCreationResult$Success")) {
            Logger.printInfo(() -> "Could not create the saved tracks playlist: " + result);
            return;
        }

        Object playlist = result.getClass().getMethod("getPlaylist").invoke(result);
        String urn = String.valueOf(playlist.getClass().getMethod("getUrn").invoke(playlist));
        preferences.edit().putString(PLAYLIST_URN, urn).apply();
        Logger.printInfo(() -> "Created the saved tracks playlist " + urn);
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
                // Hidden by the user, or the whole option is off: the empty server playlist stays out of sight.
                if (saved == null || (Settings.isSavedPlaylistEnabled() && !Settings.isSavedPlaylistHidden())) return value;
                List<Object> items = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    try {
                        if (saved.equals(String.valueOf(item.getClass().getMethod("getUrn").invoke(item)))) continue;
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
     * The saved playlist has no tracks on the server, which makes SoundCloud load it from the network
     * every time. Its tracks are local, so the stored playlist is used as is.
     */
    public static boolean useStoredPlaylist(Object urn, Object response) {
        return response != null && response.getClass().getName().endsWith("SingleItemResponse$Found")
                && isSavedPlaylist(urn);
    }

    private static final String EXCLUDED = "saved_playlist_excluded";

    public static Object playlistOperations() {
        return playlistOperations;
    }

    /** Removes a track from the saved playlist without deleting the file or the download. */
    public static void exclude(String entry) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        java.util.Set<String> excluded = new java.util.HashSet<>(preferences.getStringSet(EXCLUDED, new java.util.HashSet<>()));
        excluded.add(entry);
        preferences.edit().putStringSet(EXCLUDED, excluded).apply();
    }

    /** Downloaded track entries first, newest imported files after them. */
    public static List<String> getEntries() {
        SharedPreferences preferences = preferences();
        java.util.Set<String> excluded = preferences == null ? new java.util.HashSet<>()
                : preferences.getStringSet(EXCLUDED, new java.util.HashSet<>());
        List<String> entries = new ArrayList<>();
        for (String id : DownloadTrackPatch.getDownloadedTrackIds()) {
            String entry = "soundcloud:tracks:" + id;
            if (!excluded.contains(entry)) entries.add(entry);
        }
        Context context = Utils.getContext();
        if (context != null) {
            for (java.io.File file : LocalMusic.getFiles(context)) {
                String entry = LocalAdditions.fileEntry(file);
                if (!excluded.contains(entry)) entries.add(entry);
            }
        }
        return entries;
    }
}
