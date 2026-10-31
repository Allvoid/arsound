package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.OwnPlaylists;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Keeps the "Imported" playlist on SoundCloud in step with the app: creates it when it is missing, finds it again
 * after a reinstall, renames it, and hides or deletes the copies that older versions left behind. All of it runs
 * on a background thread, some time after the app starts.
 */
final class SavedPlaylistSync {
    private static final String API = "https://api-v2.soundcloud.com/";
    // The earlier local-only rename stored "saved_playlist_title"; a new key makes it run again on the server.
    private static final String PLAYLIST_TITLE = "saved_playlist_server_title";
    private static final String COPIES_DELETED = "saved_playlist_copies_deleted_v2";
    /** Titles this playlist had in any language and version. */
    private static final Set<String> KNOWN_TITLES = new HashSet<>(Arrays.asList(
            SavedPlaylist.TITLE_RU, SavedPlaylist.TITLE_EN, "Скачанные и импортированные", "Downloaded and imported"));

    private static volatile boolean postsChecked;

    private SavedPlaylistSync() {
    }

    /** Makes sure the playlist exists; runs on a background thread. */
    static void ensureExists() throws Exception {
        SharedPreferences preferences = SavedPlaylist.preferences();
        Logger.printInfo(() -> "Checking saved playlist, enabled: " + Settings.isSavedPlaylistEnabled()
                + ", stored: " + (preferences == null ? null : preferences.getString(SavedPlaylist.PLAYLIST_URN, null)));
        if (!Settings.isSavedPlaylistEnabled() || preferences == null) return;

        String urn = preferences.getString(SavedPlaylist.PLAYLIST_URN, null);
        if (urn != null) {
            String[] response = DownloadTrackPatch.apiGet(API + "playlists/" + Urns.idOf(urn));
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

    /**
     * Playlists created by older versions were called "Downloaded and imported". Renamed on the server:
     * a local edit through the app was overwritten by the next sync. Runs on a background thread.
     */
    private static void renameIfNeeded(SharedPreferences preferences, String urn) {
        String title = SavedPlaylist.title();
        if (title.equals(preferences.getString(PLAYLIST_TITLE, null))) return;
        try {
            String id = Urns.idOf(urn);
            JSONObject body = new JSONObject().put("playlist", new JSONObject().put("title", title));
            int code = DownloadTrackPatch.apiSend("PUT", API + "playlists/" + id, body.toString());
            if (code / 100 != 2) {
                Logger.printInfo(() -> "Could not rename the saved tracks playlist: HTTP " + code);
                return;
            }
            preferences.edit().putString(PLAYLIST_TITLE, title).apply();
            PlaylistOps operations = PlaylistOps.current();
            if (operations != null) operations.sync(operations.playlistUrn(urn));
            Logger.printInfo(() -> "Renamed the saved tracks playlist to " + title);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not rename the saved tracks playlist", ex);
        }
    }

    /**
     * Finds the playlist created before the app data was lost, instead of creating another one.
     * All empty library playlists with a known title count: the oldest one is used, the others are hidden.
     */
    private static boolean adoptExisting(SharedPreferences preferences) {
        List<String> found = findCopies(PlaylistPreloader.libraryItems("LOCAL_ONLY"));
        if (found.isEmpty()) found = findCopies(PlaylistPreloader.libraryItems("SYNCED"));
        if (found.isEmpty()) return false;

        // Ids grow over time: the smallest is the first playlist created.
        Collections.sort(found, (first, second) -> Long.compare(numericId(first), numericId(second)));
        String urn = found.get(0);
        Set<String> duplicates = new HashSet<>(found.subList(1, found.size()));
        preferences.edit()
                .putString(SavedPlaylist.PLAYLIST_URN, urn)
                .putStringSet(SavedPlaylist.DUPLICATES, duplicates)
                .apply();
        Logger.printInfo(() -> "Using the existing saved tracks playlist " + urn + ", hidden copies: " + duplicates.size());
        return true;
    }

    /** Hides copies made by older versions next to the playlist in use. Checked once. */
    private static void hideOtherCopies(SharedPreferences preferences, String urn) {
        if (preferences.contains(SavedPlaylist.DUPLICATES)) return;
        List<String> found = findCopies(PlaylistPreloader.libraryItems("LOCAL_ONLY"));
        found.remove(urn);
        preferences.edit().putStringSet(SavedPlaylist.DUPLICATES, new HashSet<>(found)).apply();
        Logger.printInfo(() -> "Hidden copies of the saved tracks playlist: " + found.size());
    }

    /**
     * Older versions created a new playlist after each reinstall; the empty copies stayed on the profile. A copy
     * looks like one: a known title, no tracks on the server and none added on this phone, and not the playlist in
     * use. It is deleted on SoundCloud only if the app remembers making it ({@link OwnPlaylists}); otherwise it may be
     * the user's own playlist with the same title, and it is only hidden in the app.
     */
    private static void deleteEmptyCopies(SharedPreferences preferences, String urn) {
        if (preferences.getBoolean(COPIES_DELETED, false)) return;
        try {
            String[] me = DownloadTrackPatch.apiGet(API + "me");
            if (me[1] == null) {
                Logger.printInfo(() -> "Copies of the saved tracks playlist not checked: HTTP " + me[0]);
                return;
            }
            long userId = new JSONObject(me[1]).getLong("id");
            String[] response = DownloadTrackPatch.apiGet(API + "users/" + userId + "/playlists_without_albums?limit=200");
            if (response[1] == null) {
                Logger.printInfo(() -> "Copies of the saved tracks playlist not checked: HTTP " + response[0]);
                return;
            }
            JSONArray playlists = new JSONObject(response[1]).getJSONArray("collection");
            int deleted = 0, failed = 0;
            List<String> copies = new ArrayList<>();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                String copy = "soundcloud:playlists:" + playlist.getLong("id");
                // The app creates its playlist with a title only: one with a description or artwork was edited
                // by the user and is theirs. (isNull: optString gives the text "null" for a JSON null.)
                boolean isCopy = !copy.equals(urn)
                        && KNOWN_TITLES.contains(playlist.optString("title"))
                        && playlist.optInt("track_count", -1) == 0
                        && (playlist.isNull("description") || playlist.optString("description").isEmpty())
                        && (playlist.isNull("artwork_url") || playlist.optString("artwork_url").isEmpty())
                        && LocalAdditions.getEntries(copy).isEmpty();
                String title = playlist.optString("title");
                int tracks = playlist.optInt("track_count", -1);
                Logger.printInfo(() -> "Own playlist " + copy + " \"" + title + "\", " + tracks + " tracks, a copy: " + isCopy);
                if (!isCopy) continue;
                copies.add(copy);
                if (!OwnPlaylists.isOwn(copy)) {
                    Logger.printInfo(() -> "Hidden, not deleted: " + copy + " was not made by this app");
                    continue;
                }
                int code = DownloadTrackPatch.apiDelete(API + "playlists/" + playlist.getLong("id"));
                Logger.printInfo(() -> "Deleted the empty copy " + copy + " of the saved tracks playlist: HTTP " + code);
                if (code / 100 == 2 || code == 404) deleted++; else failed++;
            }
            // Until the library syncs, the stored rows of the deleted copies stay hidden.
            Set<String> hidden = new HashSet<>(preferences.getStringSet(SavedPlaylist.DUPLICATES, new HashSet<>()));
            hidden.addAll(copies);
            SharedPreferences.Editor editor = preferences.edit().putStringSet(SavedPlaylist.DUPLICATES, hidden);
            // Also when there was nothing to delete: the scan is done, and a later start must not delete
            // anything again (a playlist the user made with the same title in the meantime).
            if (failed == 0) editor.putBoolean(COPIES_DELETED, true);
            editor.apply();
            int count = deleted;
            Logger.printInfo(() -> "Empty copies of the saved tracks playlist deleted: " + count);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not delete the copies of the saved tracks playlist", ex);
        }
    }

    /**
     * The own profile lists the user's playlists from the stored posts, which keep a deleted playlist
     * until the library is synced. Posted playlists that SoundCloud no longer has are removed from the
     * stored posts; the server is not touched. Checked once per start.
     */
    static synchronized void forgetDeletedCopies() {
        Object storage = SavedPlaylist.postsStorage();
        SharedPreferences preferences = SavedPlaylist.preferences();
        if (storage == null || preferences == null || postsChecked) return;
        postsChecked = true;
        try {
            ClassLoader loader = storage.getClass().getClassLoader();
            Class<?> urnClass = Urns.urnClass(loader);
            Set<String> copies = new LinkedHashSet<>(preferences.getStringSet(SavedPlaylist.DUPLICATES, Collections.emptySet()));
            Object posted = Rx.blockingFirst(storage.getClass().getMethod("loadPostedPlaylists", Integer.class)
                    .invoke(storage, (Object) null), 20, TimeUnit.SECONDS);
            if (posted instanceof List) {
                for (Object post : (List<?>) posted) copies.add(Urns.textOf(post));
            }
            copies.remove(SavedPlaylist.getUrn());
            for (String copy : copies) {
                String id = Urns.idOf(copy);
                String[] response = DownloadTrackPatch.apiGet(API + "playlists/" + id);
                if (!"404".equals(response[0])) continue;
                Object urn = Urns.forPlaylist(loader, id);
                storage.getClass().getMethod("removePlaylistPost", urnClass).invoke(storage, urn);
                Logger.printInfo(() -> "Removed the deleted playlist " + copy + " from the stored posts");
            }
        } catch (IOException ex) {
            // Without a network the check waits for the next start.
            Logger.printInfo(() -> "Deleted copies not checked, no network: " + ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove the deleted copies from the stored posts", ex);
        }
    }

    /** The urns of the empty library playlists that carry a title of the saved playlist. */
    private static List<String> findCopies(List<Object> items) {
        List<String> urns = new ArrayList<>();
        for (Object item : items) {
            try {
                String title = String.valueOf(item.getClass().getMethod("getTitle").invoke(item));
                int tracks = (Integer) item.getClass().getMethod("getTracksCount").invoke(item);
                if (tracks == 0 && KNOWN_TITLES.contains(title)) urns.add(Urns.textOf(item));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read a library playlist", ex);
            }
        }
        return urns;
    }

    private static long numericId(String urn) {
        try {
            return Long.parseLong(Urns.idOf(urn));
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static void create(SharedPreferences preferences) throws Exception {
        Object operations = SavedPlaylist.playlistOperations();
        if (operations == null) return;

        Object single = operations.getClass()
                .getMethod("createNewPlaylist", String.class, boolean.class, List.class)
                .invoke(operations, SavedPlaylist.title(), false, Collections.emptyList());
        Object result = Rx.blockingFirst(single, 30, TimeUnit.SECONDS);
        if (result == null || !result.getClass().getName().endsWith("PlaylistCreationResult$Success")) {
            Logger.printInfo(() -> "Could not create the saved tracks playlist: " + result);
            return;
        }

        Object playlist = result.getClass().getMethod("getPlaylist").invoke(result);
        String urn = Urns.textOf(playlist);
        OwnPlaylists.remember(urn);
        preferences.edit().putString(SavedPlaylist.PLAYLIST_URN, urn).putString(PLAYLIST_TITLE, SavedPlaylist.title()).apply();
        Logger.printInfo(() -> "Created the saved tracks playlist " + urn);
    }
}
