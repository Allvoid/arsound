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
 * The "Imported" playlist.
 * <p>
 * It is a regular, empty, private SoundCloud playlist, so the library, the playlist screen,
 * playback and shuffle work natively. Its tracks exist only on this device: every imported
 * file is appended when the app reads the playlist.
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
        return "ru".equals(Locale.getDefault().getLanguage()) ? "Импортированные" : "Imported";
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
            } catch (java.io.IOException ex) {
                // No network or requests blocked by the region guard: tried again on the next start.
                Logger.printInfo(() -> "Saved tracks playlist not checked: " + ex.getMessage());
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
            if (!"404".equals(response[0])) {
                renameIfNeeded(preferences, urn);
                hideOtherCopies(preferences, urn);
                deleteEmptyCopies(preferences, urn);
                forgetDeletedCopies();
                return;
            }
            Logger.printInfo(() -> "Saved tracks playlist was deleted, creating it again");
        }
        // After a reinstall the stored urn is gone, but the playlist still exists on the server.
        if (urn == null && adoptExisting(preferences)) return;
        create(preferences);
    }

    // The earlier local-only rename stored "saved_playlist_title"; a new key makes it run again on the server.
    private static final String PLAYLIST_TITLE = "saved_playlist_server_title";

    /**
     * Playlists created by older versions were called "Downloaded and imported". Renamed on the server:
     * a local edit through the app was overwritten by the next sync. Runs on a background thread.
     */
    private static void renameIfNeeded(SharedPreferences preferences, String urn) {
        String title = title();
        if (title.equals(preferences.getString(PLAYLIST_TITLE, null))) return;
        try {
            String id = urn.substring(urn.lastIndexOf(':') + 1);
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("playlist", new org.json.JSONObject().put("title", title));
            int code = DownloadTrackPatch.apiSend("PUT", "https://api-v2.soundcloud.com/playlists/" + id, body.toString());
            if (code / 100 != 2) {
                Logger.printInfo(() -> "Could not rename the saved tracks playlist: HTTP " + code);
                return;
            }
            preferences.edit().putString(PLAYLIST_TITLE, title).apply();
            Object operations = playlistOperations;
            if (operations != null) {
                ClassLoader loader = operations.getClass().getClassLoader();
                Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
                Object playlistUrn = urnClass.getMethod("forPlaylist", String.class).invoke(null, id);
                operations.getClass().getMethod("syncPlaylist", urnClass).invoke(operations, playlistUrn);
            }
            Logger.printInfo(() -> "Renamed the saved tracks playlist to " + title);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not rename the saved tracks playlist", ex);
        }
    }

    /** Titles this playlist had in any language and version. */
    private static final java.util.Set<String> KNOWN_TITLES = new java.util.HashSet<>(java.util.Arrays.asList(
            "Импортированные", "Imported", "Скачанные и импортированные", "Downloaded and imported"));
    /** Extra copies created by older versions after a reinstall. Hidden from lists, then deleted. */
    private static final String DUPLICATES = "saved_playlist_duplicates";

    /**
     * Finds the playlist created before the app data was lost, instead of creating another one.
     * All empty library playlists with a known title count: the oldest one is used, the others are hidden.
     */
    private static boolean adoptExisting(SharedPreferences preferences) {
        List<String> found = findCopies(app.revanced.extension.soundcloud.offline.PlaylistPreloader.libraryItems("LOCAL_ONLY"));
        if (found.isEmpty()) {
            found = findCopies(app.revanced.extension.soundcloud.offline.PlaylistPreloader.libraryItems("SYNCED"));
        }
        if (found.isEmpty()) return false;

        // Ids grow over time: the smallest is the first playlist created.
        Collections.sort(found, (first, second) -> Long.compare(idOf(first), idOf(second)));
        String urn = found.get(0);
        java.util.Set<String> duplicates = new java.util.HashSet<>(found.subList(1, found.size()));
        preferences.edit()
                .putString(PLAYLIST_URN, urn)
                .putStringSet(DUPLICATES, duplicates)
                .apply();
        Logger.printInfo(() -> "Using the existing saved tracks playlist " + urn + ", hidden copies: " + duplicates.size());
        return true;
    }

    /** Hides copies made by older versions next to the playlist in use. Checked once. */
    private static void hideOtherCopies(SharedPreferences preferences, String urn) {
        if (preferences.contains(DUPLICATES)) return;
        List<String> found = findCopies(app.revanced.extension.soundcloud.offline.PlaylistPreloader.libraryItems("LOCAL_ONLY"));
        found.remove(urn);
        preferences.edit().putStringSet(DUPLICATES, new java.util.HashSet<>(found)).apply();
        Logger.printInfo(() -> "Hidden copies of the saved tracks playlist: " + found.size());
    }

    private static final String COPIES_DELETED = "saved_playlist_copies_deleted_v2";

    /**
     * Older versions created a new playlist after each reinstall; the empty copies stayed on the profile.
     * Each copy is deleted on SoundCloud once it is confirmed to be one: a known title, no tracks on the
     * server and none added on this phone, and not the playlist in use.
     */
    private static void deleteEmptyCopies(SharedPreferences preferences, String urn) {
        if (preferences.getBoolean(COPIES_DELETED, false)) return;
        try {
            String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
            if (me[1] == null) {
                Logger.printInfo(() -> "Copies of the saved tracks playlist not checked: HTTP " + me[0]);
                return;
            }
            long userId = new org.json.JSONObject(me[1]).getLong("id");
            String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/users/" + userId
                    + "/playlists_without_albums?limit=200");
            if (response[1] == null) {
                Logger.printInfo(() -> "Copies of the saved tracks playlist not checked: HTTP " + response[0]);
                return;
            }
            org.json.JSONArray playlists = new org.json.JSONObject(response[1]).getJSONArray("collection");
            int deleted = 0, failed = 0;
            List<String> copies = new ArrayList<>();
            for (int i = 0; i < playlists.length(); i++) {
                org.json.JSONObject playlist = playlists.getJSONObject(i);
                String copy = "soundcloud:playlists:" + playlist.getLong("id");
                boolean isCopy = !copy.equals(urn)
                        && KNOWN_TITLES.contains(playlist.optString("title"))
                        && playlist.optInt("track_count", -1) == 0
                        && LocalAdditions.getEntries(copy).isEmpty();
                String title = playlist.optString("title");
                int tracks = playlist.optInt("track_count", -1);
                Logger.printInfo(() -> "Own playlist " + copy + " \"" + title + "\", " + tracks + " tracks, a copy: " + isCopy);
                if (!isCopy) continue;
                copies.add(copy);
                int code = DownloadTrackPatch.apiDelete("https://api-v2.soundcloud.com/playlists/" + playlist.getLong("id"));
                Logger.printInfo(() -> "Deleted the empty copy " + copy + " of the saved tracks playlist: HTTP " + code);
                if (code / 100 == 2 || code == 404) deleted++; else failed++;
            }
            // Until the library syncs, the stored rows of the deleted copies stay hidden.
            java.util.Set<String> hidden = new java.util.HashSet<>(preferences.getStringSet(DUPLICATES, new java.util.HashSet<>()));
            hidden.addAll(copies);
            SharedPreferences.Editor editor = preferences.edit().putStringSet(DUPLICATES, hidden);
            if (deleted > 0 && failed == 0) editor.putBoolean(COPIES_DELETED, true);
            editor.apply();
            int count = deleted;
            Logger.printInfo(() -> "Empty copies of the saved tracks playlist deleted: " + count);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not delete the copies of the saved tracks playlist", ex);
        }
    }

    private static volatile Object postsStorage;

    /** Injection point: the constructor of {@code PostsStorage}, the stored posts of the user. */
    public static void setPostsStorage(Object instance) {
        boolean first = postsStorage == null;
        postsStorage = instance;
        if (!first) return;
        // After the sign-in, like the check of the saved playlist.
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(12_000);
                forgetDeletedCopies();
            } catch (InterruptedException ignored) {
            }
        });
    }

    /**
     * The own profile lists the user's playlists from the stored posts, which keep a deleted playlist
     * until the library is synced. Posted playlists that SoundCloud no longer has are removed from the
     * stored posts; the server is not touched. Checked once per start.
     */
    private static synchronized void forgetDeletedCopies() {
        Object storage = postsStorage;
        SharedPreferences preferences = preferences();
        if (storage == null || preferences == null || postsChecked) return;
        postsChecked = true;
        try {
            ClassLoader loader = storage.getClass().getClassLoader();
            Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
            java.util.Set<String> copies = new java.util.LinkedHashSet<>(preferences.getStringSet(DUPLICATES, Collections.emptySet()));
            Object posted = Rx.blockingFirst(storage.getClass().getMethod("loadPostedPlaylists", Integer.class)
                    .invoke(storage, (Object) null), 20, java.util.concurrent.TimeUnit.SECONDS);
            if (posted instanceof List) {
                for (Object post : (List<?>) posted) copies.add(String.valueOf(post.getClass().getMethod("getUrn").invoke(post)));
            }
            copies.remove(getUrn());
            for (String copy : copies) {
                String id = copy.substring(copy.lastIndexOf(':') + 1);
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + id);
                if (!"404".equals(response[0])) continue;
                Object urn = urnClass.getMethod("forPlaylist", String.class).invoke(null, id);
                storage.getClass().getMethod("removePlaylistPost", urnClass).invoke(storage, urn);
                Logger.printInfo(() -> "Removed the deleted playlist " + copy + " from the stored posts");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove the deleted copies from the stored posts", ex);
        }
    }

    private static volatile boolean postsChecked;

    private static List<String> findCopies(List<Object> items) {
        List<String> urns = new ArrayList<>();
        for (Object item : items) {
            try {
                String title = String.valueOf(item.getClass().getMethod("getTitle").invoke(item));
                int tracks = (Integer) item.getClass().getMethod("getTracksCount").invoke(item);
                if (tracks == 0 && KNOWN_TITLES.contains(title)) {
                    urns.add(String.valueOf(item.getClass().getMethod("getUrn").invoke(item)));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read a library playlist", ex);
            }
        }
        return urns;
    }

    private static long idOf(String urn) {
        try {
            return Long.parseLong(urn.substring(urn.lastIndexOf(':') + 1));
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
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
        preferences.edit().putString(PLAYLIST_URN, urn).putString(PLAYLIST_TITLE, title()).apply();
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
                java.util.Set<String> duplicates = preferences == null ? Collections.emptySet()
                        : preferences.getStringSet(DUPLICATES, Collections.emptySet());
                // Hidden by the user, or the whole option is off: the empty server playlist stays out of sight.
                boolean hideSaved = saved != null && !(Settings.isSavedPlaylistEnabled() && !Settings.isSavedPlaylistHidden());
                if (!hideSaved && duplicates.isEmpty()) return value;
                List<Object> items = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    try {
                        String urn = String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
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
        if (isSavedPlaylist(urn)) return true;
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

    /** Imported files, newest first. Tracks downloaded by Arsound are not part of this playlist. */
    public static List<String> getEntries() {
        SharedPreferences preferences = preferences();
        java.util.Set<String> excluded = preferences == null ? new java.util.HashSet<>()
                : preferences.getStringSet(EXCLUDED, new java.util.HashSet<>());
        List<String> entries = new ArrayList<>();
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
