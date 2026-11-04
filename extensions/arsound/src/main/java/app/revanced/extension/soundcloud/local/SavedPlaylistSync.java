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
 * Keeps the "Imported" playlist in step with the app: makes it on this phone when it is missing, and hides or deletes the
 * copies that older versions left on the account. All of it runs on a background thread, some time after the app starts.
 */
final class SavedPlaylistSync {
    private static final String API = "https://api-v2.soundcloud.com/";
    // A new key: the playlist this phone used on the server has become one of the copies.
    private static final String COPIES_DELETED = "saved_playlist_copies_deleted_v3";
    /** Titles this playlist had in any language and version. */
    private static final Set<String> KNOWN_TITLES = new HashSet<>(Arrays.asList(
            SavedPlaylist.TITLE_RU, SavedPlaylist.TITLE_EN, "Скачанные и импортированные", "Downloaded and imported"));

    private static volatile boolean postsChecked;

    private SavedPlaylistSync() {
    }

    /**
     * Makes sure the playlist exists on this phone; runs on a background thread. An older version kept it as an empty playlist on
     * SoundCloud: what it held moves to the local one, and the empty playlist leaves the account.
     */
    static void ensureExists() throws Exception {
        SharedPreferences preferences = SavedPlaylist.preferences();
        Logger.printInfo(() -> "Checking saved playlist, enabled: " + Settings.isSavedPlaylistEnabled()
                + ", stored: " + (preferences == null ? null : preferences.getString(SavedPlaylist.PLAYLIST_URN, null)));
        if (!Settings.isSavedPlaylistEnabled() || preferences == null) return;
        String urn = LocalPlaylists.IMPORTED;
        if (!LocalPlaylists.ensure(urn, SavedPlaylist.title())) return;
        String stored = preferences.getString(SavedPlaylist.PLAYLIST_URN, null);
        if (!urn.equals(stored)) {
            if (stored != null) {
                OwnPlaylists.remember(stored);  // the app used it as its own: it may go
                LocalPlaylists.move(stored, urn);
            }
            preferences.edit().putString(SavedPlaylist.PLAYLIST_URN, urn).apply();
        }
        hideOtherCopies(preferences, urn);
        deleteEmptyCopies(preferences, urn);
        forgetDeletedCopies();
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
            copies.removeIf(LocalPlaylists::isLocal);
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
}
