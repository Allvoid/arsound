package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
    /** Downloaded track ids already answered in the running check of downloads, so a cut-short check goes on. */
    private static final String SWEEP_CHECKED = "sweep_checked";

    private static final String TRACK_PREFIX = "soundcloud:tracks:";
    private static final long SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** A sync that returns far fewer tracks may be broken; at most this many tracks are checked at once. */
    private static final int MAX_CHECKS = 30;
    /** How long a playlist is left alone after the API itself failed (an expired token, an outage, a rate limit). */
    private static final long RETRY_DELAY_MS = 15L * 60 * 1000;
    /** An answer that is neither "exists" nor "deleted" is asked again this many times, then the track is forgotten. */
    private static final int MAX_FAILED_CHECKS = 3;
    /** Failed requests in a row after which the check of downloads stops until the next start. */
    private static final int MAX_SWEEP_FAILURES = 3;

    private static final Set<String> checking = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Playlist to the time before which its removed tracks are not checked again. */
    private static final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    /** Track urn to the number of checks that got an unclear answer. */
    private static final Map<String, Integer> failedChecks = new ConcurrentHashMap<>();
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
        restoreListed(preferences, playlist, now);
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

        if (missing.isEmpty() || isPostponed(playlist) || !checking.add(playlist)) return;
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
                break;
            }
            if ("404".equals(code)) {
                keepDeleted(playlist, urn, positions.get(i));
                changed = true;
            } else if (code.startsWith("2") || "403".equals(code)) {
                // The track exists: it was removed from the playlist on purpose.
                Logger.printInfo(() -> "Track " + urn + " was removed from " + playlist + ", not deleted");
            } else if (isApiFailure(code)) {
                // The API itself is failing, so the answer says nothing about the track: the rest waits too.
                Logger.printInfo(() -> "The API failed while checking " + playlist + ", the check is postponed");
                retryAt.put(playlist, System.currentTimeMillis() + RETRY_DELAY_MS);
                break;
            } else if (failedChecks.merge(urn, 1, Integer::sum) < MAX_FAILED_CHECKS) {
                continue;
            } else {
                // The same unclear answer again and again: it is not going to change, the track is forgotten.
                Logger.printInfo(() -> "Gave up checking the removed track " + urn);
            }
            failedChecks.remove(urn);
            removeFromSnapshot(playlist, urn);
        }
        if (changed) LocalAdditions.notifyPlaylistChanged(playlist);
    }

    /** The API itself failed (no valid response, an expired token, a rate limit, a server error). */
    private static boolean isApiFailure(String code) {
        return "-1".equals(code) || "401".equals(code) || "429".equals(code) || code.startsWith("5");
    }

    private static boolean isPostponed(String playlist) {
        Long until = retryAt.get(playlist);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        retryAt.remove(playlist);
        return false;
    }

    /**
     * A track kept in this playlist as deleted, which SoundCloud lists in it again, is not deleted: the earlier
     * answer was wrong, or the track was restored. It is no longer kept here, and no longer greyed out unless
     * another playlist keeps it too.
     */
    private static void restoreListed(SharedPreferences preferences, String playlist, Set<String> listed) {
        Map<String, Integer> kept = readPositions(KEPT_PREFIX + playlist);
        Set<String> restored = new HashSet<>(kept.keySet());
        restored.retainAll(listed);
        if (restored.isEmpty()) return;

        Logger.printInfo(() -> "SoundCloud lists " + restored + " in " + playlist + " again, not deleted");
        kept.keySet().removeAll(restored);
        writePositions(KEPT_PREFIX + playlist, kept);
        Set<String> unmarked = new HashSet<>(restored);
        for (String key : new ArrayList<>(preferences.getAll().keySet())) {
            if (key.startsWith(KEPT_PREFIX)) unmarked.removeAll(readPositions(key).keySet());
        }
        Set<String> deleted = new HashSet<>(preferences.getStringSet(DELETED, new HashSet<>()));
        if (deleted.removeAll(unmarked)) {
            preferences.edit().putStringSet(DELETED, deleted).apply();
            deletedCache = deleted;
        }
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
            // Written under a temporary name: a process killed in the middle must not leave a half copy among the imports.
            File part = LocalMusic.newPartFile(context);
            File target;
            boolean moved = false;
            try {
                try (InputStream input = new FileInputStream(download); OutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                // The name is taken only now, so no other import can take it while the copy is written.
                target = LocalMusic.moveIntoImports(context, part, (title != null ? title : "SoundCloud " + id) + extension);
                moved = true;
            } finally {
                if (!moved) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
            converted.put(id, target.getPath());
            // Written at once: a kill before the write would leave the copy unknown, and the next import would make a second one.
            preferences.edit().putString(CONVERTED, converted.toString()).commit();
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

        // Ids answered in this round so far: a round cut short by the network goes on from there at the next start.
        Set<String> checked = new HashSet<>(preferences.getStringSet(SWEEP_CHECKED, new HashSet<>()));
        int found = 0;
        int failures = 0;
        boolean complete = true;
        for (String id : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (checked.contains(id) || isConverted(id) || DownloadTrackPatch.getDownloadedFile(id) == null) continue;
            String code = null;
            try {
                code = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id)[0];
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                complete = false;
                break;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not check the downloaded track " + id + ": " + ex);
            }
            if (code == null || isApiFailure(code)) {
                // No answer about this track: it waits for the next start. Several in a row mean the network is down.
                // The round stays open, so the next start asks again about the tracks skipped here.
                complete = false;
                if (++failures >= MAX_SWEEP_FAILURES) {
                    Logger.printInfo(() -> "Download check stopped, the network or the API is failing");
                    break;
                }
                continue;
            }
            failures = 0;
            if ("404".equals(code) && importDownload(id) != null) {
                found++;
                Logger.printInfo(() -> "Downloaded track " + id + " was deleted on SoundCloud, imported");
            }
            checked.add(id);
            preferences.edit().putStringSet(SWEEP_CHECKED, new HashSet<>(checked)).apply();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                complete = false;
                break;
            }
        }
        if (complete) preferences.edit().putLong(LAST_SWEEP, System.currentTimeMillis()).remove(SWEEP_CHECKED).apply();
        int total = found;
        Logger.printInfo(() -> "Download check: deleted and imported " + total);
        if (found > 0) {
            String saved = SavedPlaylist.getUrn();
            if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
        }
    }

    /**
     * Whether a file in the imported music is the copy of a downloaded track that SoundCloud deleted.
     * Nothing can bring such a file back, so it is never deleted as unplayed.
     */
    public static boolean isConvertedCopy(File file) {
        SharedPreferences preferences = preferences();
        if (preferences == null || file == null) return false;
        try {
            JSONObject converted = new JSONObject(preferences.getString(CONVERTED, "{}"));
            for (Iterator<String> keys = converted.keys(); keys.hasNext(); ) {
                if (file.getPath().equals(converted.optString(keys.next(), null))) return true;
            }
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the imported copies of deleted tracks", ex);
            // Unknown is protected: a file kept by mistake costs space, a file deleted by mistake is lost.
            return true;
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
