package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The tracks each playlist shows, local additions included, remembered on this device.
 * <p>
 * SoundCloud counts only the tracks on its server, so a playlist filled on this phone says "0 Tracks"
 * in the library. The list of each playlist screen is kept here: the library cells show its size and,
 * after the download icon, how many of the tracks play from a file (downloaded by Arsound or imported).
 * The lists of all library playlists are read in the background after start, so the numbers are there
 * before a playlist is opened.
 */
public final class PlaylistTracks {
    private static final String PREFERENCES_NAME = "arsound_playlist_tracks";
    private static final String DOWNLOADED_PREFIX = "downloaded:";
    /** An imported file in a stored list: only its kind matters for the numbers. */
    static final String IMPORTED = "imported";
    private static final String TRACK_PREFIX = "soundcloud:tracks:";
    /** Cells whose meta line is being built wait for this many tagged states at most. */
    private static final int TAG_LIMIT = 400;

    /** Playlist urn to its tracks: SoundCloud urns and {@link #IMPORTED} for files. */
    private static final Map<String, List<String>> lists = new ConcurrentHashMap<>();
    /** Playlist urn to the number of its tracks that play from a file. */
    private static final Map<String, Integer> downloaded = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private PlaylistTracks() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(DOWNLOADED_PREFIX)) {
                if (entry.getValue() instanceof Integer) downloaded.put(key.substring(DOWNLOADED_PREFIX.length()), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                String value = (String) entry.getValue();
                lists.put(key, value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split("\n"))));
            }
        }
        loaded = true;
    }

    private static boolean isImported(Object urn) {
        return urn.getClass().getName().endsWith(".LocalTrackUrn");
    }

    /**
     * Remembers the list a playlist screen shows. Called off the main thread.
     *
     * @return True if the list or the number of tracks playing from a file changed.
     */
    static boolean record(String playlistUrn, List<?> urns) {
        load();
        List<String> tracks = new ArrayList<>();
        for (Object urn : urns) tracks.add(isImported(urn) ? IMPORTED : String.valueOf(urn));
        int count = countDownloaded(tracks);
        boolean changed = !tracks.equals(lists.get(playlistUrn));
        Integer previous = downloaded.get(playlistUrn);
        changed |= previous == null || previous != count;
        lists.put(playlistUrn, tracks);
        downloaded.put(playlistUrn, count);
        SharedPreferences preferences = preferences();
        if (changed && preferences != null) {
            preferences.edit()
                    .putString(playlistUrn, String.join("\n", tracks))
                    .putInt(DOWNLOADED_PREFIX + playlistUrn, count)
                    .apply();
        }
        Logger.printInfo(() -> "Playlist " + playlistUrn + ": " + tracks.size() + " tracks, " + count + " play from a file");
        return changed;
    }

    /** Reads files: call off the main thread. */
    private static int countDownloaded(List<String> tracks) {
        Context context = Utils.getContext();
        int count = 0;
        for (String track : tracks) {
            if (track.equals(IMPORTED)) {
                count++;
            } else if (context != null && track.startsWith(TRACK_PREFIX) && DownloadTrackPatch.getDownloadState(context,
                    track.substring(TRACK_PREFIX.length())) == DownloadTrackPatch.DownloadState.DOWNLOADED) {
                count++;
            }
        }
        return count;
    }

    /** Counts again after downloads finished or were deleted. Off the main thread. */
    public static void recountAll() {
        load();
        SharedPreferences preferences = preferences();
        SharedPreferences.Editor editor = preferences == null ? null : preferences.edit();
        for (Map.Entry<String, List<String>> entry : lists.entrySet()) {
            int count = countDownloaded(entry.getValue());
            Integer previous = downloaded.put(entry.getKey(), count);
            if (editor != null && (previous == null || previous != count)) editor.putInt(DOWNLOADED_PREFIX + entry.getKey(), count);
        }
        if (editor != null) editor.apply();
    }

    public static Integer downloadedCount(String playlistUrn) {
        load();
        return downloaded.get(playlistUrn);
    }

    /** @return True if the playlist is known to hold no SoundCloud tracks: it is empty or only imported files. */
    public static boolean hasOnlyImportedTracks(String playlistUrn) {
        load();
        List<String> tracks = lists.get(playlistUrn);
        if (tracks == null) return false;
        for (String track : tracks) if (!track.equals(IMPORTED)) return false;
        return true;
    }

    /** The playlists that hold an imported file. */
    static List<String> playlistsWithImportedTracks() {
        load();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : lists.entrySet()) {
            if (entry.getValue().contains(IMPORTED)) result.add(entry.getKey());
        }
        return result;
    }

    /**
     * Reads the lists of the given playlists the way their screens do. Blocks: call off the main thread.
     *
     * @param playlistUrns Urn strings of library playlists.
     */
    public static void recordAll(List<String> playlistUrns) {
        Object operations = SavedPlaylist.playlistOperations();
        if (operations == null) return;
        int done = 0;
        for (String urn : playlistUrns) {
            try {
                ClassLoader loader = operations.getClass().getClassLoader();
                Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
                Object playlistUrn = urnClass.getMethod("forPlaylist", String.class).invoke(null, urn.substring(urn.lastIndexOf(':') + 1));
                Object single = operations.getClass().getMethod("playlistTrackUrns", urnClass).invoke(operations, playlistUrn);
                // The same list as on the screen: local additions, kept deleted tracks, own order.
                Object screen = LocalAdditions.appendToTrackUrns(single, playlistUrn);
                if (Rx.blockingFirst(screen, 20, TimeUnit.SECONDS) != null) done++;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read the tracks of " + urn, ex);
            }
        }
        int count = done;
        Logger.printInfo(() -> "Track lists read for " + count + " of " + playlistUrns.size() + " library playlists");
    }

    // region Library cells

    /**
     * Injection point: the number of tracks in a playlist cell.
     *
     * @return The number of tracks the playlist shows, local additions included.
     */
    public static int cellTracksCount(Object playlist, int count) {
        try {
            load();
            String urn = urnOf(playlist);
            cellPlaylist.set(urn);
            List<String> tracks = lists.get(urn);
            return tracks != null ? tracks.size() : count;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not count the playlist tracks", ex);
            return count;
        }
    }

    /** Meta line states of playlist cells and the playlist urn of each, compared by identity. */
    private static final List<Object[]> tagged = new ArrayList<>();

    /** The playlist whose cell is being built: the register of the playlist gets the state. */
    private static final ThreadLocal<String> cellPlaylist = new ThreadLocal<>();

    /** Injection point: the meta line state built for a playlist cell. */
    public static void tagCell(Object viewState) {
        String urn = cellPlaylist.get();
        cellPlaylist.remove();
        if (urn == null || viewState == null) return;
        try {
            synchronized (tagged) {
                for (Iterator<Object[]> iterator = tagged.iterator(); iterator.hasNext(); ) {
                    if (((WeakReference<?>) iterator.next()[0]).get() == null) iterator.remove();
                }
                if (tagged.size() >= TAG_LIMIT) tagged.remove(0);
                tagged.add(new Object[]{new WeakReference<>(viewState), urn});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not tag the playlist cell", ex);
        }
    }

    private static String taggedUrn(Object viewState) {
        synchronized (tagged) {
            for (int i = tagged.size() - 1; i >= 0; i--) {
                if (((WeakReference<?>) tagged.get(i)[0]).get() == viewState) return (String) tagged.get(i)[1];
            }
        }
        return null;
    }

    /** The state whose items are being built: its register is reused before the list is returned. */
    private static final ThreadLocal<Object> building = new ThreadLocal<>();

    /** Injection point: the start of {@code MetaLabelUtilsKt.a(builder, viewState, context)}. */
    public static void beforeItems(Object viewState) {
        building.set(viewState);
    }

    /**
     * Injection point: the items of a meta line. A playlist cell gets the number of tracks playing
     * from a file after its number of tracks.
     */
    public static List<?> withDownloadedCount(List<?> items) {
        Object viewState = building.get();
        building.remove();
        if (viewState == null) return items;
        try {
            String urn = taggedUrn(viewState);
            Integer count = urn == null ? null : downloadedCount(urn);
            if (count == null || count == 0) return items;
            List<Object> result = new ArrayList<>(items);
            addCount(result, count, viewState.getClass().getClassLoader());
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the downloaded count", ex);
            return items;
        }
    }

    /** Adds the download icon with the number after the number of tracks, or after the type. */
    static void addCount(List<Object> items, int count, ClassLoader loader) throws Exception {
        Object downloadedItem = Class.forName("com.soundcloud.android.ui.components.labels.MetaLabelType$IconWithText", false, loader)
                .getConstructor(String.class, int.class)
                .newInstance(String.valueOf(count), Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_labels_downloaded"));
        int position = Math.min(1, items.size());
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getClass().getName().contains("$Tracks$")) position = i + 1;
        }
        items.add(position, downloadedItem);
    }

    // endregion

    private static String urnOf(Object playlist) throws Exception {
        return String.valueOf(playlist.getClass().getMethod("getUrn").invoke(playlist));
    }
}
