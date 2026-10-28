package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadIndex;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * Downloaded tracks that SoundCloud deleted become imported tracks: the downloaded file is copied into the imported
 * files, once, with a readable name made from the title remembered while the track was on screen.
 */
final class DownloadImporter {
    /** Track id to the imported copy of its download. */
    private static final String CONVERTED = "converted";
    /** Track id to "title — artist", remembered for downloaded tracks while they are on screen. */
    private static final String TITLES = "titles";
    private static final String LAST_SWEEP = "last_download_sweep";
    /** Downloaded track ids already answered in the running check of downloads, so a cut-short check goes on. */
    private static final String SWEEP_CHECKED = "sweep_checked";

    private static final long SWEEP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Failed requests in a row after which the check of downloads stops until the next start. */
    private static final int MAX_SWEEP_FAILURES = 3;
    private static final String TRACK_URL = "https://api-v2.soundcloud.com/tracks/";

    private static final Set<String> titledIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private DownloadImporter() {
    }

    private static SharedPreferences preferences() {
        return LocalPrefs.open(RemovedTracks.PREFERENCES_NAME);
    }

    /**
     * Copies the downloaded file of a deleted track into the imported files, once.
     *
     * @return The imported file, or null if the track was not downloaded or its file cannot be read.
     */
    static synchronized File importDownload(String id) {
        SharedPreferences preferences = preferences();
        Context context = Utils.getContext();
        if (preferences == null || context == null) return null;
        try {
            JSONObject converted = new JSONObject(preferences.getString(CONVERTED, "{}"));
            String existing = converted.optString(id, null);
            if (existing != null && new File(existing).isFile()) return new File(existing);

            DownloadIndex.Entry download = DownloadTrackPatch.downloadedEntry(id);
            if (download == null) return null;

            String name = download.name();
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot) : ".mp3";
            String title = getTitle(id);
            // Through the media store where the path is closed to the app (Android 10).
            File target = LocalMusic.importThroughPart(context, (title != null ? title : "SoundCloud " + id) + extension, output -> {
                try (InputStream input = download.open(context)) {
                    FileIo.copy(input, output);
                }
            });
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
    static void sweepDownloads() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        if (System.currentTimeMillis() - preferences.getLong(LAST_SWEEP, 0) < SWEEP_INTERVAL_MS) return;

        // Ids answered in this round so far: a round cut short by the network goes on from there at the next start.
        Set<String> checked = new HashSet<>(preferences.getStringSet(SWEEP_CHECKED, new HashSet<>()));
        int found = 0;
        int failures = 0;
        boolean complete = true;
        for (String id : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (checked.contains(id) || isConverted(id) || DownloadTrackPatch.downloadedEntry(id) == null) continue;
            TrackAnswer answer = null;
            try {
                answer = TrackAnswer.of(DownloadTrackPatch.apiGet(TRACK_URL + id)[0]);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                complete = false;
                break;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not check the downloaded track " + id + ": " + ex);
            }
            if (answer == null || answer == TrackAnswer.API_FAILED) {
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
            if (answer == TrackAnswer.DELETED && importDownload(id) != null) {
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
            if (saved != null) PlaylistChanges.playlistChanged(saved);
        }
    }

    /**
     * Whether a file in the imported music is the copy of a downloaded track that SoundCloud deleted.
     * Nothing can bring such a file back, so it is never deleted as unplayed.
     */
    static boolean isConvertedCopy(File file) {
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
            synchronized (DownloadImporter.class) {
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
                title = TrackTitles.get(RemovedTracks.TRACK_PREFIX + id);
            }
            return title;
        } catch (Exception ex) {
            return null;
        }
    }
}
