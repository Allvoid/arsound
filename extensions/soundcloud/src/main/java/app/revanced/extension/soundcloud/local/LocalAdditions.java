package app.revanced.extension.soundcloud.local;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Tracks added to a playlist or album only on this device, including playlists of other users.
 * <p>
 * The additions are appended when SoundCloud reads the track list of a playlist, so the screen,
 * shuffle and the play queue include them. They are never written to the SoundCloud database or
 * sent to the server: edits of own playlists drop them before saving.
 * <p>
 * An entry is either a SoundCloud track ({@code soundcloud:tracks:123}) or an imported file
 * ({@code file:/data/.../imported/name.mp3}).
 */
@SuppressWarnings("unused")
public final class LocalAdditions {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String ADDITIONS = "additions";
    private static final String RECENT_PLAYLISTS = "recent_playlists";
    private static final int RECENT_LIMIT = 30;
    private static final String FILE_PREFIX = "file:";
    private static final String TRACK_ROW_TAG = "arsound_local_add_row";
    private static final String PLAYLIST_ROW_TAG = "arsound_local_additions_row";

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    private LocalAdditions() {
    }

    private static String text(String russian, String english) {
        return RUSSIAN ? russian : english;
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    // region Storage

    /** Playlist urn to its local entries, in order of addition. */
    private static synchronized Map<String, List<String>> readAdditions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        SharedPreferences preferences = preferences();
        if (preferences == null) return result;
        try {
            JSONObject json = new JSONObject(preferences.getString(ADDITIONS, "{}"));
            for (java.util.Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String playlist = keys.next();
                JSONArray entries = json.getJSONArray(playlist);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) list.add(entries.getString(i));
                result.put(playlist, list);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read local additions", ex);
        }
        return result;
    }

    private static synchronized void writeAdditions(Map<String, List<String>> additions) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, List<String>> entry : additions.entrySet()) {
                if (!entry.getValue().isEmpty()) json.put(entry.getKey(), new JSONArray(entry.getValue()));
            }
            preferences.edit().putString(ADDITIONS, json.toString()).apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save local additions", ex);
        }
    }

    /** Entries added by the user to this playlist. */
    public static List<String> getEntries(String playlistUrn) {
        List<String> entries = readAdditions().get(playlistUrn);
        return entries == null ? new ArrayList<>() : entries;
    }

    /** Entries shown in the playlist: for the saved tracks playlist, all downloads and imported files. */
    public static List<String> getShownEntries(String playlistUrn) {
        List<String> entries = getEntries(playlistUrn);
        if (SavedPlaylist.isSavedPlaylist(playlistUrn)) {
            for (String entry : SavedPlaylist.getEntries()) if (!entries.contains(entry)) entries.add(entry);
        }
        return entries;
    }

    /** @return False if the entry was already added. */
    public static boolean add(String playlistUrn, String entry) {
        Map<String, List<String>> additions = readAdditions();
        List<String> entries = additions.get(playlistUrn);
        if (entries == null) additions.put(playlistUrn, entries = new ArrayList<>());
        if (entries.contains(entry)) return false;
        entries.add(entry);
        writeAdditions(additions);
        return true;
    }

    public static void remove(String playlistUrn, String entry) {
        Map<String, List<String>> additions = readAdditions();
        List<String> entries = additions.get(playlistUrn);
        if (entries != null && entries.remove(entry)) writeAdditions(additions);
    }

    public static String fileEntry(File file) {
        return FILE_PREFIX + file.getPath();
    }

    /** Remembers opened playlists, so they can be picked as a target. Newest first: urn and title. */
    private static synchronized List<String[]> readRecentPlaylists() {
        List<String[]> result = new ArrayList<>();
        SharedPreferences preferences = preferences();
        if (preferences == null) return result;
        try {
            JSONArray json = new JSONArray(preferences.getString(RECENT_PLAYLISTS, "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject item = json.getJSONObject(i);
                result.add(new String[]{item.getString("urn"), item.optString("title")});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read recent playlists", ex);
        }
        return result;
    }

    // endregion

    // region Injection points

    /** Called when the playlist screen receives a playlist. */
    public static void onPlaylistOpened(Object playlistItem) {
        try {
            String urn = String.valueOf(playlistItem.getClass().getMethod("getUrn").invoke(playlistItem));
            String title = String.valueOf(playlistItem.getClass().getMethod("getTitle").invoke(playlistItem));
            Utils.runOnBackgroundThread(() -> rememberPlaylist(urn, title));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remember playlist", ex);
        }
    }

    private static synchronized void rememberPlaylist(String urn, String title) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        List<String[]> recent = readRecentPlaylists();
        recent.removeIf(item -> item[0].equals(urn));
        recent.add(0, new String[]{urn, title});
        try {
            JSONArray json = new JSONArray();
            for (int i = 0; i < Math.min(recent.size(), RECENT_LIMIT); i++) {
                json.put(new JSONObject().put("urn", recent.get(i)[0]).put("title", recent.get(i)[1]));
            }
            preferences.edit().putString(RECENT_PLAYLISTS, json.toString()).apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save recent playlists", ex);
        }
    }

    /**
     * Wraps {@code playlistTrackUrns(playlistUrn)} to append the local additions.
     *
     * @param single      {@code Single<List<Urn>>}.
     * @param playlistUrn The playlist {@code Urn}.
     */
    public static Object appendToTrackUrns(Object single, Object playlistUrn) {
        String key = String.valueOf(playlistUrn);
        int count = getShownEntries(key).size();
        Logger.printInfo(() -> "Track urns requested for " + key + ", local additions: " + count);
        if (count == 0) return single;
        try {
            return Rx.mapSingle(single, value -> {
                List<Object> urns = new ArrayList<>((List<?>) value);
                for (String entry : getShownEntries(key)) {
                    Object urn = toUrn(single.getClass().getClassLoader(), entry);
                    if (urn != null && !urns.contains(urn)) urns.add(urn);
                }
                return urns;
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not append local additions", ex);
            return single;
        }
    }

    private static Object toUrn(ClassLoader loader, String entry) {
        try {
            if (entry.startsWith(FILE_PREFIX)) {
                File file = new File(entry.substring(FILE_PREFIX.length()));
                if (!file.isFile()) return null;
                Class<?> localUrn = Class.forName("com.soundcloud.android.foundation.domain.LocalTrackUrn", false, loader);
                Object companion = localUrn.getField("Companion").get(null);
                return companion.getClass().getMethod("fromFile", File.class).invoke(companion, file);
            }
            String id = DownloadTrackPatch.parseTrackId(entry);
            if (id == null) return null;
            Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
            return urnClass.getMethod("forTrack", String.class).invoke(null, id);
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid local addition " + entry, ex);
            return null;
        }
    }

    /**
     * SoundCloud's track repository drops urns of local files from track lists. Called at the start
     * of {@code LocalFileAwareTrackRepository.tracks()}: if the list has local files, it requests
     * the other tracks as usual and inserts the tracks of the files at their positions.
     *
     * @param repository The {@code LocalFileAwareTrackRepository}.
     * @param urns       The requested {@code Iterable<TrackUrn>}.
     * @param strategy   The {@code LoadStrategy}.
     * @return The {@code Observable<ListResponse<Track>>}, or null to run the original method.
     */
    public static Object tracksWithLocalFiles(Object repository, Object urns, Object strategy) {
        try {
            List<Object> requested = new ArrayList<>();
            List<Object> remote = new ArrayList<>();
            for (Object urn : (Iterable<?>) urns) {
                requested.add(urn);
                if (!urn.getClass().getName().endsWith(".LocalTrackUrn")) remote.add(urn);
            }
            if (remote.size() == requested.size()) return null;
            Logger.printInfo(() -> "Adding " + (requested.size() - remote.size()) + " local file tracks to a track list");

            ClassLoader loader = repository.getClass().getClassLoader();
            Class<?> trackUrnClass = Class.forName("com.soundcloud.android.foundation.domain.TrackUrn", false, loader);
            Class<?> strategyClass = Class.forName("com.soundcloud.android.foundation.domain.repository.LoadStrategy", false, loader);
            Method track = repository.getClass().getMethod("track", trackUrnClass, strategyClass);
            Method tracks = repository.getClass().getMethod("tracks", Iterable.class, strategyClass);
            Object response = tracks.invoke(repository, remote, strategy);

            return Rx.mapObservable(response, value -> {
                try {
                    return mergeLocalTracks(loader, value, requested, urn -> {
                        try {
                            Object found = Rx.blockingFirst(track.invoke(repository, urn, strategy), 5, TimeUnit.SECONDS);
                            return found == null ? null : found.getClass().getMethod("getItem").invoke(found);
                        } catch (Exception ex) {
                            Logger.printInfo(() -> "Local file track unavailable: " + urn + " " + ex);
                            return null;
                        }
                    });
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not merge local file tracks", ex);
                    return value;
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local file tracks", ex);
            return null;
        }
    }

    private static Object mergeLocalTracks(ClassLoader loader, Object response, List<Object> requested,
                                           java.util.function.Function<Object, Object> loadLocal) throws Exception {
        String prefix = "com.soundcloud.android.foundation.domain.repository.ListResponse$Success$";
        Class<?> total = Class.forName(prefix + "Total", false, loader);
        Class<?> partial = Class.forName(prefix + "Partial", false, loader);

        List<?> found;
        if (total.isInstance(response)) found = (List<?>) total.getMethod("getItems").invoke(response);
        else if (partial.isInstance(response)) found = (List<?>) partial.getMethod("getFound").invoke(response);
        else return response;

        Map<Object, Object> byUrn = new java.util.HashMap<>();
        for (Object item : found) byUrn.put(item.getClass().getMethod("getTrackUrn").invoke(item), item);

        List<Object> merged = new ArrayList<>();
        for (Object urn : requested) {
            Object item = byUrn.get(urn);
            if (item == null && urn.getClass().getName().endsWith(".LocalTrackUrn")) item = loadLocal.apply(urn);
            if (item != null) merged.add(item);
        }

        if (total.isInstance(response)) return total.getConstructor(List.class).newInstance(merged);
        Object missing = partial.getMethod("getMissing").invoke(response);
        Object exception = partial.getMethod("getException").invoke(response);
        return partial.getConstructors()[0].getParameterTypes().length == 3
                ? partial.getConstructor(List.class, List.class,
                        Class.forName("com.soundcloud.android.foundation.domain.repository.RepositoryException", false, loader))
                .newInstance(merged, missing, exception)
                : response;
    }

    /**
     * Removes local additions from a track set before SoundCloud saves an edited playlist.
     *
     * @param playlistUrn The playlist {@code Urn}.
     * @param tracks      The {@code Set<Urn>} to save.
     */
    public static Object withoutLocalAdditions(Object playlistUrn, Object tracks) {
        String playlist = String.valueOf(playlistUrn);
        List<String> entries = getEntries(playlist);
        java.util.Set<Object> result = new java.util.LinkedHashSet<>();
        boolean addedLocally = false;
        for (Object urn : (Iterable<?>) tracks) {
            if (urn.getClass().getName().endsWith(".LocalTrackUrn")) {
                // An imported file picked in the native "Add to playlist" screen.
                String entry = entryOf(urn);
                if (entry != null && add(playlist, entry)) addedLocally = true;
                continue;
            }
            if (entries.contains(String.valueOf(urn))) continue;
            result.add(urn);
        }
        if (addedLocally) notifyPlaylistChanged(playlist);
        return result;
    }

    // endregion

    // region Menus

    public static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        try {
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(TRACK_ROW_TAG);
            if (existing != null) parent.removeView(existing);

            Context context = dialog.getContext();
            String entry = String.valueOf(trackUrn);
            ViewGroup row = DownloadTrackPatch.createMenuRow(context,
                    text("Добавить в плейлист локально", "Add to playlist locally"),
                    "ic_actions_playlist_add_to_playlist", v -> {
                        Context activity = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
                        dialog.dismiss();
                        pickPlaylist(activity, entry);
                    });
            row.setTag(TRACK_ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local playlist row", ex);
        }
    }

    public static void addPlaylistMenuRow(Dialog dialog, String playlistUrn) {
        try {
            LinearLayout menuItems = dialog.findViewById(
                    Utils.getResourceIdentifier(ResourceType.ID, "playlistBottomSheetMenuItems"));
            if (menuItems == null || menuItems.findViewWithTag(PLAYLIST_ROW_TAG) != null) return;

            Context context = dialog.getContext();
            int count = getEntries(playlistUrn).size();
            ViewGroup row = DownloadTrackPatch.createMenuRow(context,
                    text("Локальные треки", "Local tracks") + (count > 0 ? " (" + count + ")" : ""),
                    "ic_actions_playlist_add_to_playlist", v -> {
                        Context activity = dialog.getOwnerActivity() != null ? dialog.getOwnerActivity() : context;
                        dialog.dismiss();
                        manageAdditions(activity, playlistUrn);
                    });
            row.setTag(PLAYLIST_ROW_TAG);
            menuItems.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local additions row", ex);
        }
    }

    /** Lets the user pick a playlist: own playlists and recently opened playlists and albums. */
    public static void pickPlaylist(Context context, String entry) {
        List<String[]> targets = readRecentPlaylists();
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
                boolean added = add(target[0], entry);
                rememberTitle(entry);
                notifyPlaylistChanged(target[0]);
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

    // region Titles

    private static final String TITLES = "titles";

    /** A readable name for an entry: the cached SoundCloud title. */
    public static synchronized String getTitle(String entry) {
        if (entry.startsWith(FILE_PREFIX)) return null;
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        try {
            return new JSONObject(preferences.getString(TITLES, "{}")).optString(entry, null);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Loads and caches the title of a SoundCloud track entry, so local lists can show it offline. */
    public static void rememberTitle(String entry) {
        if (entry.startsWith(FILE_PREFIX) || getTitle(entry) != null) return;
        String id = DownloadTrackPatch.parseTrackId(entry);
        if (id == null) return;
        Utils.runOnBackgroundThread(() -> {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id);
                if (response[1] == null) return;
                JSONObject track = new JSONObject(response[1]);
                JSONObject user = track.optJSONObject("user");
                String title = track.optString("title") + (user == null ? "" : " — " + user.optString("username"));
                synchronized (LocalAdditions.class) {
                    SharedPreferences preferences = preferences();
                    if (preferences == null) return;
                    JSONObject titles = new JSONObject(preferences.getString(TITLES, "{}"));
                    titles.put(entry, title);
                    preferences.edit().putString(TITLES, titles.toString()).apply();
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load track title for " + entry + ": " + ex);
            }
        });
    }

    /** Converts entries to urns for playback. Missing files are skipped. */
    public static List<Object> toUrns(ClassLoader loader, List<String> entries) {
        List<Object> urns = new ArrayList<>();
        for (String entry : entries) {
            Object urn = toUrn(loader, entry);
            if (urn != null) urns.add(urn);
        }
        return urns;
    }

    // endregion

    /** "Local tracks" of a playlist: what was added on this phone, with removal and adding own files. */
    private static void manageAdditions(Context context, String playlistUrn) {
        Utils.runOnBackgroundThread(() -> {
            List<String> entries = getEntries(playlistUrn);
            List<LocalMusic.Track> imported = LocalMusic.getTracks(context);
            for (String entry : entries) rememberTitle(entry);
            Utils.runOnMainThread(() -> {
                List<LocalSheet.Item> items = LocalSheet.items();
                items.add(new LocalSheet.Item(text("Добавить свой файл", "Add my file"), "ic_actions_playlist_add_to_playlist",
                        () -> pickImportedFile(context, playlistUrn, imported)));
                for (String entry : entries) {
                    String label = describe(entry, imported);
                    items.add(new LocalSheet.Item(label, "ic_actions_playlist_remove_from_playlist", () -> {
                        remove(playlistUrn, entry);
                        notifyPlaylistChanged(playlistUrn);
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
                add(playlistUrn, fileEntry(track.file));
                notifyPlaylistChanged(playlistUrn);
                Toast.makeText(context, text("Добавлено: " + track.title, "Added: " + track.title), Toast.LENGTH_SHORT).show();
            }));
        }
        LocalSheet.show(context, text("Импортированные файлы", "Imported files"), null, items);
    }

    public static String describe(String entry, List<LocalMusic.Track> imported) {
        if (entry.startsWith(FILE_PREFIX)) {
            String path = entry.substring(FILE_PREFIX.length());
            for (LocalMusic.Track track : imported) if (track.file.getPath().equals(path)) return track.title;
            return new File(path).getName();
        }
        String title = getTitle(entry);
        return title != null ? title : text("Трек SoundCloud ", "SoundCloud track ") + DownloadTrackPatch.parseTrackId(entry);
    }

    // endregion

    // region Native playlist actions

    /** Tells open playlist screens to reload their track list. */
    public static void notifyPlaylistChanged(String playlistUrn) {
        Object operations = SavedPlaylist.playlistOperations();
        if (operations == null) return;
        try {
            ClassLoader loader = operations.getClass().getClassLoader();
            Object urn = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader)
                    .getMethod("forPlaylist", String.class)
                    .invoke(null, playlistUrn.substring(playlistUrn.lastIndexOf(':') + 1));
            operations.getClass().getMethod("notifyPlaylistsUpdated", java.util.Set.class, java.util.Set.class)
                    .invoke(operations, Collections.singleton(urn), Collections.emptySet());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not refresh playlist " + playlistUrn, ex);
        }
    }

    private static String entryOf(Object trackUrn) {
        if (trackUrn.getClass().getName().endsWith(".LocalTrackUrn")) {
            try {
                return fileEntry((File) trackUrn.getClass().getMethod("getFile").invoke(trackUrn));
            } catch (Exception ex) {
                return null;
            }
        }
        return String.valueOf(trackUrn);
    }

    /**
     * Called at the start of the native "Remove from playlist" action.
     *
     * @return True if the track was a local track of this playlist and was removed here.
     */
    public static boolean removeFromPlaylist(Object playlistUrn, Object trackUrn) {
        try {
            String playlist = String.valueOf(playlistUrn);
            String entry = entryOf(trackUrn);
            if (entry == null) return false;

            boolean local = getEntries(playlist).contains(entry);
            boolean saved = SavedPlaylist.isSavedPlaylist(playlist) && SavedPlaylist.getEntries().contains(entry);
            if (!local && !saved) return false;

            if (local) remove(playlist, entry);
            if (saved) SavedPlaylist.exclude(entry);
            notifyPlaylistChanged(playlist);
            Logger.printInfo(() -> "Removed local track " + entry + " from " + playlist);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove local track", ex);
            return false;
        }
    }

    // endregion
}
