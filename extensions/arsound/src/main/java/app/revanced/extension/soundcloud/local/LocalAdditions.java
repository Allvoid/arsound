package app.revanced.extension.soundcloud.local;

import android.app.Dialog;
import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Tracks added to a playlist or album only on this device, including playlists of other users.
 * <p>
 * The additions are appended when SoundCloud reads the track list of a playlist, so the screen,
 * shuffle and the play queue include them. They are never written to the SoundCloud database or
 * sent to the server: edits of own playlists drop them before saving.
 * <p>
 * An entry is either a SoundCloud track ({@code soundcloud:tracks:123}) or an imported file
 * ({@code file:/data/.../imported/name.mp3}), see {@link Entry}.
 * <p>
 * This class is the entry point for the patched app and for the rest of Arsound. The work is done by
 * {@link AdditionsStore} (what is saved), {@link LocalFileTracks} (tracks of files in lists),
 * {@link AdditionsMenus} (menu rows and pickers), {@link TrackTitles} and {@link PlaylistChanges}.
 */
@SuppressWarnings("unused")
public final class LocalAdditions {
    private LocalAdditions() {
    }

    // region Storage

    /** Entries added by the user to this playlist. */
    public static List<String> getEntries(String playlistUrn) {
        return AdditionsStore.entries(playlistUrn);
    }

    /** Entries shown in the playlist: for the saved tracks playlist, all downloads and imported files. */
    public static List<String> getShownEntries(String playlistUrn) {
        List<String> entries = getEntries(playlistUrn);
        if (SavedPlaylist.isSavedPlaylist(playlistUrn)) {
            // A set beside the list: the saved playlist holds every download and imported file.
            java.util.Set<String> seen = new java.util.HashSet<>(entries);
            for (String entry : SavedPlaylist.getEntries()) if (seen.add(entry)) entries.add(entry);
        }
        return entries;
    }

    /** @return False if the entry was already added. */
    public static boolean add(String playlistUrn, String entry) {
        return AdditionsStore.add(playlistUrn, entry);
    }

    /**
     * Adds many entries with one read and one write, in the given order.
     *
     * @return How many were new: the entries already in the playlist, and repeats, are left out.
     */
    public static int addAll(String playlistUrn, Collection<String> newEntries) {
        return AdditionsStore.addAll(playlistUrn, newEntries);
    }

    /** Replaces the added entries of a playlist, keeping the given order. */
    public static void setEntries(String playlistUrn, List<String> entries) {
        AdditionsStore.setEntries(playlistUrn, entries);
    }

    public static void remove(String playlistUrn, String entry) {
        AdditionsStore.remove(playlistUrn, entry);
    }

    /**
     * Removes an entry from every playlist, once its file is deleted.
     *
     * @return The playlists that held the entry.
     */
    public static List<String> forgetEntry(String entry) {
        return AdditionsStore.forget(entry);
    }

    public static String fileEntry(File file) {
        return Entry.ofFile(file).stored();
    }

    /** The opened playlists, newest first: urn and title. */
    static List<String[]> readRecentPlaylists() {
        return AdditionsStore.recentPlaylists();
    }

    // endregion

    // region Injection points

    /** Called when the playlist screen receives a playlist. */
    public static void onPlaylistOpened(Object playlistItem) {
        try {
            String urn = Urns.textOf(playlistItem);
            String title = String.valueOf(playlistItem.getClass().getMethod("getTitle").invoke(playlistItem));
            Utils.runOnBackgroundThread(() -> AdditionsStore.rememberPlaylist(urn, title));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remember playlist", ex);
        }
    }

    /**
     * Injection point, in SoundCloud's database cleanup ({@code DatabaseCleanupWorker.doWork}): the track urns kept
     * because a stored playlist names them. The SoundCloud tracks added on this phone join them, so their titles,
     * covers and lengths stay in the database and the playlists show them without a network.
     *
     * @param kept The {@code List<Urn>} of tracks to keep; added to in place.
     */
    public static void keepInCleanup(List<Object> kept) {
        try {
            ClassLoader loader = LocalAdditions.class.getClassLoader();
            int before = kept.size();
            for (String stored : AdditionsStore.allEntries()) {
                String id = Entry.parse(stored).trackIdToLoad();
                if (id != null) kept.add(Urns.forTrack(loader, id));
            }
            int added = kept.size() - before;
            Logger.printInfo(() -> "Database cleanup: kept " + added + " tracks added on this phone");
        } catch (Exception ex) {
            Logger.printException(() -> "Database cleanup: the tracks added on this phone are not kept", ex);
        }
    }

    /**
     * Wraps {@code playlistTrackUrns(playlistUrn)} to append the local additions.
     *
     * @param single      {@code Single<List<Urn>>}.
     * @param playlistUrn The playlist {@code Urn}.
     */
    public static Object appendToTrackUrns(Object single, Object playlistUrn) {
        return appendToTrackUrns(single, playlistUrn, false);
    }

    /** The same for playback: tracks deleted on SoundCloud are shown, but not queued. */
    public static Object appendToPlaybackTrackUrns(Object single, Object playlistUrn) {
        return appendToTrackUrns(single, playlistUrn, true);
    }

    private static Object appendToTrackUrns(Object single, Object playlistUrn, boolean playback) {
        String key = String.valueOf(playlistUrn);
        try {
            // Always mapped: the list is also watched for tracks that SoundCloud deleted.
            return Rx.mapSingle(single, value -> {
                try {
                    return withLocalAdditions(key, value, single.getClass().getClassLoader(), playback);
                } catch (Exception ex) {
                    // A damaged record must not turn into an error of the whole playlist: its own tracks are shown.
                    Logger.printException(() -> "Could not add the local additions of " + key, ex);
                    return value;
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not append local additions", ex);
            return single;
        }
    }

    /** The playlist's urns with its local additions, the tracks SoundCloud deleted and its own order. */
    private static Object withLocalAdditions(String key, Object value, ClassLoader loader, boolean playback) throws Exception {
        List<Object> urns = new ArrayList<>((List<?>) value);
        // What SoundCloud lists itself: the library cell adds only the difference to its own number.
        int serverSize = urns.size();
        RemovedTracks.onTrackList(key, urns);
        app.revanced.extension.soundcloud.recommendations.DuplicateFilter.filterSystemPlaylist(key, urns);
        List<String> shown = getShownEntries(key);
        Logger.printInfo(() -> "Track urns requested for " + key + ", local additions: " + shown.size());
        // A set beside the list: the saved playlist adds every download, each checked against the list.
        java.util.Set<Object> present = new java.util.HashSet<>(urns);
        List<String> skipped = new ArrayList<>();
        for (String entry : shown) {
            Object urn = toUrn(loader, entry);
            if (urn != null && present.add(urn)) {
                urns.add(urn);
            } else {
                skipped.add(entry + (urn == null ? " (no file)" : " (already in the list)"));
            }
        }
        if (!skipped.isEmpty()) Logger.printInfo(() -> "Local additions of " + key + " not shown: " + skipped);
        RemovedTracks.placeKept(key, urns, loader, !playback);
        Object ordered = TrackOrder.apply(key, urns);
        if (!playback) PlaylistHeader.onScreenTracks(key, (List<?>) ordered, serverSize);
        return ordered;
    }

    static Object toUrn(ClassLoader loader, String entry) {
        return Entry.parse(entry).toUrn(loader);
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

    /**
     * SoundCloud's track repository drops urns of local files from track lists. Called at the start
     * of {@code LocalFileAwareTrackRepository.tracks()}.
     *
     * @return The {@code Observable<ListResponse<Track>>}, or null to run the original method.
     */
    public static Object tracksWithLocalFiles(Object repository, Object urns, Object strategy) {
        return LocalFileTracks.withLocalFiles(repository, urns, strategy);
    }

    /**
     * Removes local additions from a track set before SoundCloud saves an edited playlist.
     *
     * @param playlistUrn The playlist {@code Urn}.
     * @param tracks      The {@code Set<Urn>} to save.
     */
    public static Object withoutLocalAdditions(Object playlistUrn, Object tracks) {
        String playlist = String.valueOf(playlistUrn);
        Set<Object> result = new LinkedHashSet<>();
        boolean addedLocally = false;
        // The editors only see the server track list, so a SoundCloud urn here is a server track, even when
        // the same track was also added locally: it stays, otherwise saving removes it from the server.
        for (Object urn : (Iterable<?>) tracks) {
            if (Urns.isLocalFile(urn)) {
                // An imported file picked in the native "Add to playlist" screen.
                String entry = entryOf(urn);
                if (entry != null && add(playlist, entry)) addedLocally = true;
                continue;
            }
            // A track deleted on SoundCloud, kept here greyed out: the server would not accept it.
            if (RemovedTracks.isKept(playlist, String.valueOf(urn))) continue;
            result.add(urn);
        }
        // The server keeps 500 tracks in a playlist: the ones past that stay on this phone.
        if (PlaylistLimit.moveOverflowLocally(playlist, result)) addedLocally = true;
        if (addedLocally) notifyPlaylistChanged(playlist);
        return result;
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

            if (RemovedTracks.removeKept(playlist, entry)) {
                notifyPlaylistChanged(playlist);
                Logger.printInfo(() -> "Removed deleted track " + entry + " from " + playlist);
                return true;
            }

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

    /** The entry of a track urn: the file entry of an imported file, the urn text of a SoundCloud track. */
    static String entryOf(Object trackUrn) {
        if (Urns.isLocalFile(trackUrn)) {
            try {
                return fileEntry(Urns.fileOf(trackUrn));
            } catch (Exception ex) {
                return null;
            }
        }
        return String.valueOf(trackUrn);
    }

    // endregion

    // region Menus

    public static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        AdditionsMenus.addTrackMenuRow(dialog, trackUrn);
    }

    /** @return The file of an imported track, or null for any other urn. */
    public static File importedFileOf(Object trackUrn) {
        return Urns.menuFileOf(trackUrn);
    }

    /** "Set cover" in the menu of an imported file. */
    public static void addTrackCoverRow(Dialog dialog, File audio) {
        AdditionsMenus.addTrackCoverRow(dialog, audio);
    }

    /** @param menuData The data SoundCloud built the menu from, to see whether the playlist can be edited. */
    public static void addPlaylistMenuRow(Dialog dialog, String playlistUrn, Object menuData) {
        AdditionsMenus.addPlaylistMenuRows(dialog, playlistUrn, menuData);
    }

    /** Lets the user pick a playlist: own playlists and recently opened playlists and albums. */
    public static void pickPlaylist(Context context, String entry) {
        AdditionsMenus.pickPlaylist(context, entry);
    }

    // endregion

    // region Titles

    /** A readable name for an entry: the cached SoundCloud title. */
    public static String getTitle(String entry) {
        return TrackTitles.get(entry);
    }

    /** Loads and caches the title of a SoundCloud track entry, so local lists can show it offline. */
    public static void rememberTitle(String entry) {
        TrackTitles.remember(entry);
    }

    /** Loads and caches the titles of SoundCloud track entries that have none yet. */
    public static void rememberTitles(Collection<String> entries) {
        TrackTitles.rememberAll(entries);
    }

    // endregion

    // region Native playlist actions

    /** Tells open playlist screens to reload their track list, and the library to show the new number of tracks. */
    public static void notifyPlaylistChanged(String playlistUrn) {
        PlaylistChanges.playlistChanged(playlistUrn);
    }

    /** Tells the open playlists that show an imported file to load its track again, with a new cover. */
    public static void notifyTrackChanged(File audio) {
        PlaylistChanges.trackChanged(audio);
    }

    // endregion
}
