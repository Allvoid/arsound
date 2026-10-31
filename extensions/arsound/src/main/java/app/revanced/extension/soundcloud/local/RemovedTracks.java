package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    static final String PREFERENCES_NAME = "arsound_removed_tracks";
    /** The SoundCloud tracks of a playlist, one urn per line, in SoundCloud's order. */
    private static final String SNAPSHOT_PREFIX = "snapshot_";
    /** Deleted tracks kept in a playlist: urn to its position. */
    private static final String KEPT_PREFIX = "kept_";
    /** Imported copies of deleted downloaded tracks put back into a playlist: file entry to its position. */
    private static final String PLACED_PREFIX = "placed_";
    /** Deleted tracks that were not downloaded: shown greyed out. */
    private static final String DELETED = "deleted";

    static final String TRACK_PREFIX = TrackEntry.PREFIX;
    private static final String TRACK_URL = "https://api-v2.soundcloud.com/tracks/";
    /** A sync that returns far fewer tracks may be broken; at most this many tracks are checked at once. */
    private static final int MAX_CHECKS = 30;
    /** How long a playlist is left alone after the API itself failed (an expired token, an outage, a rate limit). */
    private static final long RETRY_DELAY_MS = 15L * 60 * 1000;
    /** An answer that is neither "exists" nor "deleted" is asked again this many times, then the track is forgotten. */
    private static final int MAX_FAILED_CHECKS = 3;

    private static final Set<String> checking = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Playlist to the time before which its removed tracks are not checked again. */
    private static final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    /** Track urn to the number of checks that got an unclear answer. */
    private static final Map<String, Integer> failedChecks = new ConcurrentHashMap<>();
    private static volatile Set<String> deletedCache;
    private static final PositionStore POSITIONS = new PositionStore(PREFERENCES_NAME);

    private RemovedTracks() {
    }

    private static SharedPreferences preferences() {
        return LocalPrefs.open(PREFERENCES_NAME);
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
                ? new ArrayList<>() : new ArrayList<>(Arrays.asList(stored.split("\n")));

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
            TrackAnswer answer;
            try {
                answer = TrackAnswer.of(DownloadTrackPatch.apiGet(TRACK_URL + id)[0]);
            } catch (Exception ex) {
                // No network: the track stays in the snapshot and is checked the next time.
                Logger.printInfo(() -> "Could not check the removed track " + urn + ": " + ex);
                break;
            }
            if (answer == TrackAnswer.DELETED) {
                keepDeleted(playlist, urn, positions.get(i));
                changed = true;
            } else if (answer == TrackAnswer.EXISTS) {
                // The track exists: it was removed from the playlist on purpose.
                Logger.printInfo(() -> "Track " + urn + " was removed from " + playlist + ", not deleted");
            } else if (answer == TrackAnswer.API_FAILED) {
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
        if (changed) PlaylistChanges.playlistChanged(playlist);
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
        Map<String, Integer> kept = POSITIONS.read(KEPT_PREFIX + playlist);
        Set<String> restored = new HashSet<>(kept.keySet());
        restored.retainAll(listed);
        if (restored.isEmpty()) return;

        Logger.printInfo(() -> "SoundCloud lists " + restored + " in " + playlist + " again, not deleted");
        kept.keySet().removeAll(restored);
        POSITIONS.write(KEPT_PREFIX + playlist, kept);
        Set<String> unmarked = new HashSet<>(restored);
        for (String key : new ArrayList<>(preferences.getAll().keySet())) {
            if (key.startsWith(KEPT_PREFIX)) unmarked.removeAll(POSITIONS.read(key).keySet());
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
        List<String> snapshot = new ArrayList<>(Arrays.asList(stored.split("\n")));
        if (snapshot.remove(urn)) preferences.edit().putString(SNAPSHOT_PREFIX + playlist, String.join("\n", snapshot)).apply();
    }

    /** A deleted track: its imported copy, if it was downloaded, or a greyed-out entry takes its place. */
    private static void keepDeleted(String playlist, String urn, int position) {
        String id = urn.substring(TRACK_PREFIX.length());
        File imported = DownloadImporter.importDownload(id);
        synchronized (RemovedTracks.class) {
            if (imported != null) {
                String entry = Entry.ofFile(imported).stored();
                LocalAdditions.add(playlist, entry);
                POSITIONS.put(PLACED_PREFIX + playlist, entry, position);
                Logger.printInfo(() -> "Deleted track " + urn + " is now the imported file " + imported);
            } else {
                POSITIONS.put(KEPT_PREFIX + playlist, urn, position);
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

    // region Downloads (see DownloadImporter)

    /** Checks every downloaded track once a week; the deleted ones become imported tracks. */
    public static void sweepDownloads() {
        DownloadImporter.sweepDownloads();
    }

    /** Whether a file in the imported music is the copy of a downloaded track that SoundCloud deleted. */
    public static boolean isConvertedCopy(File file) {
        return DownloadImporter.isConvertedCopy(file);
    }

    /** Remembers the name of a downloaded track shown on screen, so its imported copy gets a readable name. */
    static void rememberTitle(String urn, String title, String artist) {
        DownloadImporter.rememberTitle(urn, title, artist);
    }

    /** Whether the title of this track is still to be remembered: a downloaded track not titled in this run. */
    static boolean wantsTitle(String urn) {
        return DownloadImporter.wantsTitle(urn);
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
        Map<String, Integer> kept = includeDeleted ? POSITIONS.read(KEPT_PREFIX + playlist) : new HashMap<>();
        Map<String, Integer> placed = POSITIONS.read(PLACED_PREFIX + playlist);
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
        return POSITIONS.read(KEPT_PREFIX + playlist).containsKey(urn);
    }

    /** Removes a kept deleted track from a playlist. @return False if it was not kept there. */
    static synchronized boolean removeKept(String playlist, String urn) {
        Map<String, Integer> kept = POSITIONS.read(KEPT_PREFIX + playlist);
        if (kept.remove(urn) == null) return false;
        POSITIONS.write(KEPT_PREFIX + playlist, kept);
        return true;
    }

    // endregion
}
