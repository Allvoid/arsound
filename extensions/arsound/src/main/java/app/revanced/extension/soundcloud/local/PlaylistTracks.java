package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The tracks each playlist shows, local additions included, remembered on this device.
 * <p>
 * SoundCloud counts only the tracks on its server, so a playlist filled on this phone says "0 Tracks"
 * in the library. The list of each playlist screen is kept here: the library cells show SoundCloud's
 * number plus the tracks that exist only on this phone and, after the download icon, how many of the
 * tracks play from a file (downloaded by Arsound or imported). The lists of all library playlists are
 * read in the background after start, so the numbers are there before a playlist is opened.
 * <p>
 * The list is never shown instead of SoundCloud's number: it is old as soon as a track is added in
 * SoundCloud's own sheet or on another device. Only the difference to the server's part of it is kept
 * on top of the number SoundCloud gives now. The size of the server's part is the floor of that number:
 * SoundCloud's "Add to playlist" sheet leaves a count of only the tracks just added until the next sync.
 */
public final class PlaylistTracks {
    private static final String PREFERENCES_NAME = "arsound_playlist_tracks";
    private static final String DOWNLOADED_PREFIX = "downloaded:";
    private static final String SERVER_PREFIX = "server:";
    /** An imported file in a stored list: only its kind matters for the numbers. */
    static final String IMPORTED = "imported";
    private static final String TRACK_PREFIX = "soundcloud:tracks:";
    /** Cells whose meta line is being built wait for this many tagged states at most. */
    private static final int TAG_LIMIT = 400;
    /** A burst of changes (an import of many files, a batch) is read once. */
    private static final long REFRESH_DELAY_MS = 300;
    /** A playlist whose number keeps moving is not read again more often than this. */
    private static final long COUNT_REFRESH_PAUSE_MS = 2_000;

    /** Playlist urn to its tracks: SoundCloud urns and {@link #IMPORTED} for files. */
    private static final Map<String, List<String>> lists = new ConcurrentHashMap<>();
    /** Playlist urn to the number of its tracks that play from a file. */
    private static final Map<String, Integer> downloaded = new ConcurrentHashMap<>();
    /**
     * Playlist urn to the number of tracks its list has on top of what SoundCloud lists itself: local
     * additions and kept deleted tracks, minus filtered ones. The part of the list that is not
     * SoundCloud's own, to be added to the number SoundCloud gives now. Absent for a list remembered by an
     * older version, which did not keep the server part.
     */
    private static final Map<String, Integer> extras = new ConcurrentHashMap<>();
    /** Playlist urn to the number of tracks SoundCloud's own list had when the list was last read. */
    private static final Map<String, Integer> serverSizes = new ConcurrentHashMap<>();
    /** Playlist urn to the number SoundCloud gave its library cell last: another one means the playlist changed. */
    private static final Map<String, Integer> seenCounts = new ConcurrentHashMap<>();
    /** Playlist urn to the number its library cell was given last. */
    private static final Map<String, Integer> drawnCounts = new ConcurrentHashMap<>();
    /** Playlist urn to the time its list was last read because SoundCloud's number moved. */
    private static final Map<String, Long> countRefreshes = new ConcurrentHashMap<>();
    private static final Set<String> pendingRefresh = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean refreshScheduled = new AtomicBoolean();
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
            } else if (key.startsWith(SERVER_PREFIX)) {
                if (entry.getValue() instanceof Integer) serverSizes.put(key.substring(SERVER_PREFIX.length()), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                String value = (String) entry.getValue();
                lists.put(key, value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split("\n"))));
            }
        }
        for (Map.Entry<String, Integer> server : serverSizes.entrySet()) {
            List<String> tracks = lists.get(server.getKey());
            if (tracks != null) extras.put(server.getKey(), tracks.size() - server.getValue());
        }
        loaded = true;
    }

    private static boolean isImported(Object urn) {
        return urn.getClass().getName().endsWith(".LocalTrackUrn");
    }

    /**
     * Remembers the list a playlist screen shows. Called off the main thread.
     *
     * @param serverSize The number of tracks SoundCloud's own list had, before local additions and deleted tracks were put in.
     * @return True if the list, its server part or the number of tracks playing from a file changed.
     */
    static boolean record(String playlistUrn, List<?> urns, int serverSize) {
        load();
        List<String> tracks = new ArrayList<>();
        for (Object urn : urns) tracks.add(isImported(urn) ? IMPORTED : String.valueOf(urn));
        int count = countDownloaded(tracks);
        boolean changed = !tracks.equals(lists.get(playlistUrn));
        Integer previous = downloaded.get(playlistUrn);
        changed |= previous == null || previous != count;
        int extra = tracks.size() - serverSize;
        Integer previousExtra = extras.get(playlistUrn);
        changed |= previousExtra == null || previousExtra != extra;
        Integer previousServer = serverSizes.get(playlistUrn);
        changed |= previousServer == null || previousServer != serverSize;
        lists.put(playlistUrn, tracks);
        downloaded.put(playlistUrn, count);
        extras.put(playlistUrn, extra);
        serverSizes.put(playlistUrn, serverSize);
        SharedPreferences preferences = preferences();
        if (changed && preferences != null) {
            preferences.edit()
                    .putString(playlistUrn, String.join("\n", tracks))
                    .putInt(DOWNLOADED_PREFIX + playlistUrn, count)
                    .putInt(SERVER_PREFIX + playlistUrn, serverSize)
                    .apply();
        }
        Logger.printInfo(() -> "Playlist " + playlistUrn + ": " + tracks.size() + " tracks, " + count
                + " play from a file, " + serverSize + " from SoundCloud");
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

    // region Refresh

    /**
     * Reads the list of a playlist again soon and draws the library cells anew if their numbers changed.
     * Safe to call from any thread, as often as needed: changes of a moment are read together, one
     * playlist at a time.
     * <p>
     * Call it after the tracks of a playlist were changed on this phone. SoundCloud's library list does
     * not draw its cells again when only the local additions changed: its data is the same as before.
     * {@link LocalAdditions#notifyPlaylistChanged} calls it, so everything that tells the open
     * playlists about a change refreshes the numbers too.
     *
     * @param playlistUrn Urn string of a playlist, {@code soundcloud:playlists:...}.
     */
    public static void refreshLater(String playlistUrn) {
        if (playlistUrn == null || !playlistUrn.startsWith("soundcloud:playlists:")) return;
        pendingRefresh.add(playlistUrn);
        if (refreshScheduled.compareAndSet(false, true)) {
            Utils.runOnMainThreadDelayed(() -> Utils.runOnBackgroundThread(PlaylistTracks::refreshPending), REFRESH_DELAY_MS);
        }
    }

    private static void refreshPending() {
        try {
            while (!pendingRefresh.isEmpty()) {
                List<String> urns = new ArrayList<>(pendingRefresh);
                pendingRefresh.removeAll(urns);
                refresh(urns);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not refresh the track numbers", ex);
        } finally {
            refreshScheduled.set(false);
        }
        // Asked for while the last check ran.
        if (!pendingRefresh.isEmpty() && refreshScheduled.compareAndSet(false, true)) {
            Utils.runOnBackgroundThread(PlaylistTracks::refreshPending);
        }
    }

    private static void refresh(List<String> urns) {
        load();
        Map<String, String> before = new HashMap<>();
        for (String urn : urns) before.put(urn, shownNumbers(urn));
        recordAll(urns);
        for (String urn : urns) {
            if (shownNumbers(urn).equals(before.get(urn)) && !drawnStale(urn)) continue;
            // The library list has the same data as before, so its cells are bound again by hand.
            app.revanced.extension.soundcloud.download.DownloadProgress.redrawNow();
            return;
        }
    }

    /**
     * Whether the cell of a playlist was drawn with another number than it would get now. The list can be read
     * between the moment SoundCloud gave a cell its number and the moment the list is read again.
     */
    private static boolean drawnStale(String playlistUrn) {
        Integer count = seenCounts.get(playlistUrn);
        Integer drawn = drawnCounts.get(playlistUrn);
        return count != null && drawn != null && withLocalTracks(playlistUrn, count) != drawn;
    }

    /** What the cells of a playlist show besides SoundCloud's own number, to see if they have to be drawn again. */
    private static String shownNumbers(String playlistUrn) {
        List<String> tracks = lists.get(playlistUrn);
        Integer extra = extras.get(playlistUrn);
        String shown = tracks == null ? "-" : extra == null ? "all" + tracks.size() : extra + "/" + serverSizes.get(playlistUrn);
        return shown + ":" + downloaded.get(playlistUrn);
    }

    // endregion

    // region Library cells

    /**
     * Injection point: the number of tracks in a playlist cell.
     * <p>
     * SoundCloud's number plus the tracks that exist only on this phone, so an add made in SoundCloud
     * (its "Add to playlist" sheet, "Add music", another device) shows at once. The remembered list is
     * only used for the difference, never instead of the number, and the size of its server part only as a floor.
     *
     * @param count The number of tracks SoundCloud gives.
     * @return The number of tracks the playlist shows, local additions included.
     */
    public static int cellTracksCount(Object playlist, int count) {
        try {
            load();
            String urn = urnOf(playlist);
            cellPlaylist.set(urn);
            Integer seen = seenCounts.put(urn, count);
            if (seen != null && seen != count && lists.containsKey(urn)) refreshAfterCountChange(urn);
            int shown = withLocalTracks(urn, count);
            drawnCounts.put(urn, shown);
            return shown;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not count the playlist tracks", ex);
            return count;
        }
    }

    /**
     * The number of tracks a playlist shows, for lists that Arsound draws itself: the same number as its library cell.
     *
     * @param count The number of tracks SoundCloud gives.
     */
    static int shownCount(String playlistUrn, int count) {
        load();
        return withLocalTracks(playlistUrn, count);
    }

    /**
     * The number of tracks SoundCloud lists in a playlist: its number, or the size of the list read last if that is more.
     *
     * @param count The number of tracks SoundCloud gives.
     */
    static int serverCount(String playlistUrn, int count) {
        load();
        Integer server = serverSizes.get(playlistUrn);
        return server == null ? count : Math.max(count, server);
    }

    private static int withLocalTracks(String urn, int count) {
        Integer extra = extras.get(urn);
        Integer server = serverSizes.get(urn);
        if (extra == null || server == null) {
            // A list remembered by an older version has no server part until the lists are read after start.
            List<String> tracks = lists.get(urn);
            return tracks != null ? tracks.size() : count;
        }
        // The extra can be negative: tracks filtered out of a list, or SoundCloud counting ones it does not list.
        // SoundCloud's own number is not taken below the size of its list at the last read: its "Add to playlist"
        // sheet writes the count of the tracks just added, and the right one only comes with a sync. A track added
        // in SoundCloud since then lifts the number above that floor; one removed there shows once the list is read again.
        return Math.max(0, Math.max(count, server) + extra);
    }

    /**
     * SoundCloud's number moved: a track was added or removed in SoundCloud, on another device or by a sync.
     * The remembered list is read again, because a track added there may have been a local addition so far.
     */
    private static void refreshAfterCountChange(String urn) {
        long now = System.currentTimeMillis();
        Long last = countRefreshes.get(urn);
        if (last != null && now - last < COUNT_REFRESH_PAUSE_MS) return;
        countRefreshes.put(urn, now);
        refreshLater(urn);
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
