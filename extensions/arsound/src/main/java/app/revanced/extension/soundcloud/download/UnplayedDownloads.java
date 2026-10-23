package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Deletes the files of downloaded tracks nobody listened to for a while (Settings → Arsound → Storage), off by
 * default. The track itself stays in SoundCloud: it plays from the network and can be downloaded again. Tracks
 * imported from the phone are only included when the user asks for it separately.
 * <p>
 * A track counts as listened to when it starts playing (recorded here since this feature exists; before that, the
 * listening statistics know). A track never played counts from its download or import. The check runs once per app
 * start.
 */
public final class UnplayedDownloads {
    public static final String ENABLED = "downloads_unplayed_cleanup";
    public static final String DAYS = "downloads_unplayed_days";
    /** Imported tracks are deleted by the same rule too. Off by default, and only under {@link #ENABLED}. */
    public static final String IMPORTED = "downloads_unplayed_imported";
    public static final long DEFAULT_DAYS = 30;
    public static final long MAX_DAYS = 365;

    private static final String PREFERENCES_NAME = "arsound_played_downloads";
    /** Imported tracks are remembered by their file, downloads by their track id. */
    private static final String FILE_PREFIX = "file:";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** A track that would be deleted: a download (its track id) or an imported track. */
    public static final class Unplayed {
        public final String trackId;
        public final LocalMusic.Track imported;
        public final File file;

        Unplayed(String trackId, LocalMusic.Track imported, File file) {
            this.trackId = trackId;
            this.imported = imported;
            this.file = file;
        }
    }

    private UnplayedDownloads() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, false);
    }

    public static boolean isImportedIncluded() {
        return Settings.getBoolean(IMPORTED, false);
    }

    public static long days() {
        return Math.max(1, Math.min(MAX_DAYS, Settings.getLong(DAYS, DEFAULT_DAYS)));
    }

    /** Called when a track starts playing. Only downloaded and imported tracks are remembered. */
    public static void onTrackStarted(String trackUrn) {
        String key = null;
        File imported = LocalAdditions.importedFileOf(trackUrn);
        if (imported != null) {
            key = FILE_PREFIX + imported.getPath();
        } else {
            String trackId = DownloadTrackPatch.parseTrackId(trackUrn);
            if (trackId != null && DownloadTrackPatch.isDownloaded(trackId)) key = trackId;
        }
        if (key == null) return;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putLong(key, System.currentTimeMillis()).apply();
    }

    /** The tracks that would be deleted now. */
    public static List<Unplayed> unplayed() {
        List<Unplayed> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - days() * DAY_MS;
        SharedPreferences preferences = preferences();
        Map<String, Long> listens = app.revanced.extension.soundcloud.player.ListeningStats.lastListens();

        for (String trackId : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (DownloadProgress.isAssembling(trackId)) continue;
            // Without a known file there is nothing to delete, and its age is unknown.
            File file = DownloadTrackPatch.knownFile(trackId);
            if (file == null) continue;
            long last = Math.max(file.lastModified(), preferences == null ? 0 : preferences.getLong(trackId, 0));
            Long listened = listens.get("soundcloud:tracks:" + trackId);
            if (listened != null) last = Math.max(last, listened);
            if (last < cutoff) result.add(new Unplayed(trackId, null, file));
        }

        Context context = Utils.getContext();
        if (isImportedIncluded() && context != null) {
            // Listens of imported tracks are kept under their urn, which holds the file path.
            Map<String, Long> importedListens = new java.util.HashMap<>();
            for (Map.Entry<String, Long> listen : listens.entrySet()) {
                File file = LocalAdditions.importedFileOf(listen.getKey());
                if (file != null) importedListens.put(file.getPath(), listen.getValue());
            }
            for (LocalMusic.Track track : LocalMusic.getTracks(context)) {
                String path = track.file.getPath();
                // The copy is written at import, so its time is the import time.
                long last = Math.max(track.file.lastModified(),
                        preferences == null ? 0 : preferences.getLong(FILE_PREFIX + path, 0));
                Long listened = importedListens.get(path);
                if (listened != null) last = Math.max(last, listened);
                if (last < cutoff) result.add(new Unplayed(null, track, track.file));
            }
        }
        return result;
    }

    /** Injection point: once per app start, on a background thread. */
    public static void runAtStart() {
        if (!isEnabled()) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            int downloads = 0, imported = 0;
            for (Unplayed track : unplayed()) {
                if (track.imported != null) {
                    if (LocalMusic.delete(track.imported)) imported++;
                } else if (DownloadTrackPatch.deleteDownload(context, track.trackId)) {
                    downloads++;
                }
            }
            int downloadCount = downloads, importedCount = imported;
            Logger.printInfo(() -> "Unplayed downloads: deleted " + downloadCount + " downloaded and " + importedCount
                    + " imported files not played for " + days() + " days");
            // Forget tracks that are gone.
            SharedPreferences preferences = preferences();
            if (preferences != null && downloads + imported > 0) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : preferences.getAll().keySet()) {
                    boolean gone = key.startsWith(FILE_PREFIX)
                            ? !new File(key.substring(FILE_PREFIX.length())).exists()
                            : !DownloadTrackPatch.isDownloaded(key);
                    if (gone) editor.remove(key);
                }
                editor.apply();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Unplayed downloads: could not clean up", ex);
        }
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
