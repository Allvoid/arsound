package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * Tracks that SoundCloud deleted keep their place in playlists.
 * <p>
 * When a track is deleted on SoundCloud, the next sync silently drops it from every playlist, even if it
 * was downloaded. To notice this, the track list of each playlist is remembered. A track that disappears
 * from the list is checked once: if SoundCloud answers "not found", it was deleted, otherwise it was simply
 * removed from the playlist and is forgotten.
 * <ul>
 *     <li>A downloaded deleted track becomes an imported track: its file is copied to the imported files
 *     and put back at the same place in the playlist.</li>
 *     <li>A deleted track that was not downloaded stays in the playlist greyed out. It cannot be played,
 *     tapping it says why.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class RemovedTracks {
    private static final String PREFERENCES_NAME = "arsound_removed_tracks";
    /** The SoundCloud tracks of a playlist, one urn per line, in SoundCloud's order. */
    private static final String SNAPSHOT_PREFIX = "snapshot_";
    /** Deleted tracks kept in a playlist: urn to its position. */
    private static final String KEPT_PREFIX = "kept_";
    /** Imported copies of deleted downloaded tracks put back into a playlist: file entry to its position. */
    private static final String PLACED_PREFIX = "placed_";
    /** Deleted tracks that were not downloaded: shown greyed out. */
    private static final String DELETED = "deleted";
    /** Track id to the imported copy of its download. */
    private static final String CONVERTED = "converted";
    /** Track id to "title — artist", remembered for downloaded tracks while they are on screen. */
    private static final String TITLES = "titles";
    private static final String LAST_SWEEP = "last_download_sweep";

    private static final String TRACK_PREFIX = "soundcloud:tracks:";
    private static final long SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** A sync that returns far fewer tracks may be broken; at most this many tracks are checked at once. */
    private static final int MAX_CHECKS = 30;

    private static final Set<String> checking = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile Set<String> deletedCache;
    private static final Set<String> titledIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private RemovedTracks() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    // region Watching playlists

    /**
     * Called with the track list of a playlist as SoundCloud stored it, before local additions are added.
     * Compares it with the list seen last time and checks the tracks that disappeared in the background.
     */
    static synchronized void onTrackList(String playlist, List<?> urns) {
        SharedPreferences preferences = preferences();
        if (preferences == null || SavedPlaylist.isSavedPlaylist(playlist)) return;

        List<String> current = new ArrayList<>();
        for (Object urn : urns) {
            String value = String.valueOf(urn);
            if (value.startsWith(TRACK_PREFIX)) current.add(value);
        }
        String stored = preferences.getString(SNAPSHOT_PREFIX + playlist, null);
        List<String> previous = stored == null || stored.isEmpty()
                ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(stored.split("\n")));

        // An empty list after a full one is more likely a failed load than a playlist emptied on purpose.
        if (current.isEmpty() && !previous.isEmpty()) return;

        Set<String> now = new HashSet<>(current);
        List<String> missing = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < previous.size(); i++) {
            if (!now.contains(previous.get(i))) {
                missing.add(previous.get(i));
                positions.add(i);
            }
        }

        // The missing tracks stay in the snapshot at their places until the check decides about them.
        List<String> snapshot = new ArrayList<>(current);
        for (int i = 0; i < missing.size(); i++) snapshot.add(Math.min(positions.get(i), snapshot.size()), missing.get(i));
        String value = String.join("\n", snapshot);
        if (!value.equals(stored)) preferences.edit().putString(SNAPSHOT_PREFIX + playlist, value).apply();

        if (missing.isEmpty() || !checking.add(playlist)) return;
        Logger.printInfo(() -> "Tracks gone from " + playlist + ": " + missing);
        Utils.runOnBackgroundThread(() -> {
            try {
                check(playlist, missing.subList(0, Math.min(missing.size(), MAX_CHECKS)),
                        positions.subList(0, Math.min(positions.size(), MAX_CHECKS)));
            } finally {
                checking.remove(playlist);
            }
        });
    }

    private static void check(String playlist, List<String> missing, List<Integer> positions) {
        boolean changed = false;
        for (int i = 0; i < missing.size(); i++) {
            String urn = missing.get(i);
            String id = urn.substring(TRACK_PREFIX.length());
            String code;
            try {
                code = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id)[0];
            } catch (Exception ex) {
                // No network: the track stays in the snapshot and is checked the next time.
                Logger.printInfo(() -> "Could not check the removed track " + urn + ": " + ex);
                return;
            }
            if ("404".equals(code)) {
                keepDeleted(playlist, urn, positions.get(i));
                changed = true;
            } else if (code.startsWith("2") || "403".equals(code)) {
                // The track exists: it was removed from the playlist on purpose.
                Logger.printInfo(() -> "Track " + urn + " was removed from " + playlist + ", not deleted");
            } else {
                continue;
            }
            removeFromSnapshot(playlist, urn);
        }
        if (changed) LocalAdditions.notifyPlaylistChanged(playlist);
    }

    private static synchronized void removeFromSnapshot(String playlist, String urn) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        String stored = preferences.getString(SNAPSHOT_PREFIX + playlist, "");
        List<String> snapshot = new ArrayList<>(java.util.Arrays.asList(stored.split("\n")));
        if (snapshot.remove(urn)) preferences.edit().putString(SNAPSHOT_PREFIX + playlist, String.join("\n", snapshot)).apply();
    }

    /** A deleted track: its imported copy, if it was downloaded, or a greyed-out entry takes its place. */
    private static void keepDeleted(String playlist, String urn, int position) {
        String id = urn.substring(TRACK_PREFIX.length());
        File imported = importDownload(id);
        synchronized (RemovedTracks.class) {
            if (imported != null) {
                String entry = LocalAdditions.fileEntry(imported);
                LocalAdditions.add(playlist, entry);
                putPosition(PLACED_PREFIX + playlist, entry, position);
                Logger.printInfo(() -> "Deleted track " + urn + " is now the imported file " + imported);
            } else {
                putPosition(KEPT_PREFIX + playlist, urn, position);
                SharedPreferences preferences = preferences();
                Set<String> deleted = new HashSet<>(preferences.getStringSet(DELETED, new HashSet<>()));
                deleted.add(urn);
                preferences.edit().putStringSet(DELETED, deleted).apply();
                deletedCache = deleted;
                Logger.printInfo(() -> "Deleted track " + urn + " is kept in " + playlist + " as unavailable");
            }
        }
    }

    // endregion

    // region Downloads

    /**
     * Copies the downloaded file of a deleted track into the imported files, once.
     *
     * @return The imported file, or null if the track was not downloaded or its file cannot be read.
     */
    private static synchronized File importDownload(String id) {
        SharedPreferences preferences = preferences();
        Context context = Utils.getContext();
        if (preferences == null || context == null) return null;
        try {
            JSONObject converted = new JSONObject(preferences.getString(CONVERTED, "{}"));
            String existing = converted.optString(id, null);
            if (existing != null && new File(existing).isFile()) return new File(existing);

            File download = DownloadTrackPatch.getDownloadedFile(id);
            if (download == null) return null;

            String name = download.getName();
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot) : ".mp3";
            String title = getTitle(id);
            File target = LocalMusic.newImportFile(context, (title != null ? title : "SoundCloud " + id) + extension);
            try (InputStream input = new FileInputStream(download); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            } catch (Exception ex) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                throw ex;
            }
            converted.put(id, target.getPath());
            preferences.edit().putString(CONVERTED, converted.toString()).apply();
            LocalMusic.onFileAdded();
            return target;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not import the download of the deleted track " + id, ex);
            return null;
        }
    }

    /**
     * Tracks deleted before their playlists were watched: every downloaded track is checked once a week,
     * and the deleted ones become imported tracks. They show up in "Imported".
     */
    public static void sweepDownloads() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        if (System.currentTimeMillis() - preferences.getLong(LAST_SWEEP, 0) < SWEEP_INTERVAL_MS) return;

        int found = 0;
        for (String id : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (isConverted(id) || DownloadTrackPatch.getDownloadedFile(id) == null) continue;
            try {
                String code = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id)[0];
                if ("404".equals(code) && importDownload(id) != null) {
                    found++;
                    Logger.printInfo(() -> "Downloaded track " + id + " was deleted on SoundCloud, imported");
                }
                Thread.sleep(300);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Download check stopped: " + ex);
                return;
            }
        }
        preferences.edit().putLong(LAST_SWEEP, System.currentTimeMillis()).apply();
        int total = found;
        Logger.printInfo(() -> "Download check: deleted and imported " + total);
        if (found > 0) {
            String saved = SavedPlaylist.getUrn();
            if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
        }
    }

    private static synchronized boolean isConverted(String id) {
        SharedPreferences preferences = preferences();
        try {
            return preferences != null && new JSONObject(preferences.getString(CONVERTED, "{}")).has(id);
        } catch (Exception ex) {
            return false;
        }
    }

    /** Remembers the name of a downloaded track shown on screen, so its imported copy gets a readable name. */
    static void rememberTitle(String urn, String title, String artist) {
        String id = DownloadTrackPatch.parseTrackId(urn);
        if (id == null || title == null || titledIds.contains(id) || !DownloadTrackPatch.isDownloaded(id)) return;
        titledIds.add(id);
        Utils.runOnBackgroundThread(() -> {
            synchronized (RemovedTracks.class) {
                SharedPreferences preferences = preferences();
                if (preferences == null) return;
                try {
                    JSONObject titles = new JSONObject(preferences.getString(TITLES, "{}"));
                    String value = artist == null || artist.isEmpty() ? title : title + " — " + artist;
                    if (value.equals(titles.optString(id, null))) return;
                    titles.put(id, value);
                    preferences.edit().putString(TITLES, titles.toString()).apply();
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not remember a track title", ex);
                }
            }
        });
    }

    private static String getTitle(String id) {
        SharedPreferences preferences = preferences();
        try {
            String title = preferences == null ? null : new JSONObject(preferences.getString(TITLES, "{}")).optString(id, null);
            if (title == null) {
                // Tracks added locally have their title cached there.
                title = LocalAdditions.getTitle(TRACK_PREFIX + id);
            }
            return title;
        } catch (Exception ex) {
            return null;
        }
    }

    // endregion

    // region Track list

    /**
     * Puts deleted tracks and the imported copies of deleted downloads at their former places.
     *
     * @param urns           The track urns of the playlist, with local additions appended.
     * @param includeDeleted False for playback: a deleted track cannot be played.
     */
    static void placeKept(String playlist, List<Object> urns, ClassLoader loader, boolean includeDeleted) {
        Map<String, Integer> kept = includeDeleted ? readPositions(KEPT_PREFIX + playlist) : new java.util.HashMap<>();
        Map<String, Integer> placed = readPositions(PLACED_PREFIX + playlist);
        if (kept.isEmpty() && placed.isEmpty()) return;

        List<Object[]> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : placed.entrySet()) {
            Object urn = LocalAdditions.toUrn(loader, entry.getKey());
            // The user may have removed the imported copy from this playlist since.
            if (urn != null && urns.remove(urn)) items.add(new Object[]{entry.getValue(), urn});
        }
        for (Map.Entry<String, Integer> entry : kept.entrySet()) {
            Object urn = LocalAdditions.toUrn(loader, entry.getKey());
            if (urn == null) continue;
            urns.remove(urn);
            items.add(new Object[]{entry.getValue(), urn});
        }
        Collections.sort(items, (first, second) -> Integer.compare((Integer) first[0], (Integer) second[0]));
        for (Object[] item : items) urns.add(Math.min((Integer) item[0], urns.size()), item[1]);
    }

    public static boolean isDeleted(String urn) {
        Set<String> deleted = deletedCache;
        if (deleted == null) {
            SharedPreferences preferences = preferences();
            deleted = preferences == null ? new HashSet<>() : new HashSet<>(preferences.getStringSet(DELETED, new HashSet<>()));
            deletedCache = deleted;
        }
        return deleted.contains(urn);
    }

    /** Whether this deleted track is kept in the playlist, so it is not sent to the server with an edit. */
    static boolean isKept(String playlist, String urn) {
        return readPositions(KEPT_PREFIX + playlist).containsKey(urn);
    }

    /** Removes a kept deleted track from a playlist. @return False if it was not kept there. */
    static synchronized boolean removeKept(String playlist, String urn) {
        Map<String, Integer> kept = readPositions(KEPT_PREFIX + playlist);
        if (kept.remove(urn) == null) return false;
        writePositions(KEPT_PREFIX + playlist, kept);
        return true;
    }

    private static synchronized void putPosition(String key, String entry, int position) {
        Map<String, Integer> positions = readPositions(key);
        positions.put(entry, position);
        writePositions(key, positions);
    }

    private static synchronized Map<String, Integer> readPositions(String key) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        SharedPreferences preferences = preferences();
        if (preferences == null) return result;
        try {
            JSONObject json = new JSONObject(preferences.getString(key, "{}"));
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String entry = keys.next();
                result.put(entry, json.getInt(entry));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read " + key, ex);
        }
        return result;
    }

    private static synchronized void writePositions(String key, Map<String, Integer> positions) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        if (positions.isEmpty()) {
            preferences.edit().remove(key).apply();
            return;
        }
        preferences.edit().putString(key, new JSONObject(positions).toString()).apply();
    }

    // endregion
}
