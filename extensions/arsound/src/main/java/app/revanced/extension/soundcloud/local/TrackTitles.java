package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Titles of SoundCloud tracks that were added to playlists on this phone, cached so the lists can name them
 * offline. One worker loads the missing ones one after another.
 */
final class TrackTitles {
    private static final String TITLES = "titles";
    private static final String TRACK_URL = "https://api-v2.soundcloud.com/tracks/";

    /** Entries whose titles wait to be loaded, in the order they came. */
    private static final Set<String> pendingTitles = new LinkedHashSet<>();
    private static boolean titleWorkerRunning;

    private TrackTitles() {
    }

    /** The cached titles read once, for a whole list: reading them again for every entry parses the whole map each time. */
    static final class Snapshot {
        private final JSONObject titles;

        private Snapshot(JSONObject titles) {
            this.titles = titles;
        }

        boolean has(String entry) {
            return titles.has(entry);
        }

        /** @return The cached title of the entry, or null. */
        String get(String entry) {
            return titles.optString(entry, null);
        }
    }

    static Snapshot snapshot() {
        return new Snapshot(readTitles());
    }

    /** A readable name for an entry: the cached SoundCloud title. */
    static String get(String entry) {
        return Entry.parse(entry).cachedTitle(snapshot());
    }

    /** Loads and caches the title of a SoundCloud track entry, so local lists can show it offline. */
    static void remember(String entry) {
        rememberAll(Collections.singletonList(entry));
    }

    /**
     * Loads and caches the titles of SoundCloud track entries that have none yet. One worker reads them one
     * after another, so a merge of hundreds of tracks is not hundreds of threads and requests at once.
     * Entries that already wait for their turn are not queued again.
     */
    static void rememberAll(Collection<String> entries) {
        rememberAll(entries, null);
    }

    /** Runs to call when the worker has no titles left to load. */
    private static final List<Runnable> whenLoaded = new ArrayList<>();

    /**
     * {@link #rememberAll(Collection)}, and {@code done} runs on the main thread when the worker has loaded them (or
     * given up). Nothing to load: {@code done} does not run.
     *
     * @return Whether titles are being loaded (and {@code done} will run).
     */
    static boolean rememberAll(Collection<String> entries, Runnable done) {
        List<String> uncached = new ArrayList<>();
        Snapshot titles = snapshot();
        Set<String> gone = missing();
        for (String entry : entries) {
            if (titles.has(entry) || gone.contains(entry) || Entry.parse(entry).trackIdToLoad() == null) continue;
            uncached.add(entry);
        }
        if (uncached.isEmpty()) return false;
        boolean start;
        synchronized (pendingTitles) {
            if (done != null) whenLoaded.add(done);
            pendingTitles.addAll(uncached);
            start = !titleWorkerRunning;
            titleWorkerRunning = true;
        }
        if (start) Utils.runOnBackgroundThread(TrackTitles::loadPendingTitles);
        return true;
    }

    private static void loadPendingTitles() {
        while (true) {
            String entry;
            synchronized (pendingTitles) {
                Iterator<String> next = pendingTitles.iterator();
                if (!next.hasNext()) {
                    titleWorkerRunning = false;
                    List<Runnable> done = new ArrayList<>(whenLoaded);
                    whenLoaded.clear();
                    for (Runnable run : done) Utils.runOnMainThread(run);
                    return;
                }
                entry = next.next();
                next.remove();
            }
            try {
                loadTitle(entry);
            } catch (RegionGuard.BlockedException ex) {
                // No request leaves the phone from this IP: the rest waits for the next time the list is opened.
                synchronized (pendingTitles) {
                    pendingTitles.clear();
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load track title for " + entry + ": " + ex);
            }
        }
    }

    private static void loadTitle(String entry) throws Exception {
        String id = DownloadTrackPatch.parseTrackId(entry);
        if (id == null) return;
        String[] response = DownloadTrackPatch.apiGet(TRACK_URL + id);
        if ("404".equals(response[0])) {
            // Deleted on SoundCloud: it has no title to give, and asking again at every open of the list is for nothing.
            rememberMissing(entry);
            return;
        }
        if (response[1] == null) return;
        JSONObject track = new JSONObject(response[1]);
        JSONObject user = track.optJSONObject("user");
        String title = track.optString("title") + (user == null ? "" : " — " + user.optString("username"));
        synchronized (TrackTitles.class) {
            SharedPreferences preferences = LocalPrefs.open();
            if (preferences == null) return;
            JSONObject titles = new JSONObject(preferences.getString(TITLES, "{}"));
            titles.put(entry, title);
            preferences.edit().putString(TITLES, titles.toString()).apply();
        }
    }

    /** The entries SoundCloud answered "not found" for: deleted tracks, which have no title to load. */
    private static final String MISSING = "titles_missing";
    private static Set<String> missing;

    private static synchronized Set<String> missing() {
        if (missing == null) {
            SharedPreferences preferences = LocalPrefs.open();
            missing = preferences == null ? new java.util.HashSet<>()
                    : new java.util.HashSet<>(preferences.getStringSet(MISSING, new java.util.HashSet<>()));
        }
        return new java.util.HashSet<>(missing);
    }

    private static synchronized void rememberMissing(String entry) {
        missing();
        if (!missing.add(entry)) return;
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences != null) preferences.edit().putStringSet(MISSING, new java.util.HashSet<>(missing)).apply();
    }

    private static synchronized JSONObject readTitles() {
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return new JSONObject();
        try {
            return new JSONObject(preferences.getString(TITLES, "{}"));
        } catch (Exception ex) {
            return new JSONObject();
        }
    }
}
