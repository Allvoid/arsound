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
import app.revanced.extension.soundcloud.local.RemovedTracks;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Urns;

/**
 * Deletes the files of downloaded tracks nobody listened to for a while (Settings → Arsound → Storage), off by
 * default. The track itself stays in SoundCloud: it plays from the network and can be downloaded again. Tracks
 * imported from the phone are only included when the user asks for it separately. The copy of a downloaded track
 * that SoundCloud deleted is never included: nothing can bring it back.
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

    /** A track that would be deleted: a download or an imported track. */
    public abstract static class Unplayed {
        /** The size of its file in bytes. */
        public final long size;

        Unplayed(long size) {
            this.size = size;
        }

        /** True for a track imported from the phone, false for a download. */
        abstract boolean isImported();

        /** Deletes the file. @return True if it was deleted. */
        abstract boolean delete(Context context);

        /**
         * When the track was last used, looked up again now: it may have started playing since the list was made.
         * A start is in the preferences at once; the listening history is the one read before the loop.
         */
        abstract long lastUseNow(SharedPreferences preferences, Listens listens);
    }

    /** The last listen of every track from the listening history, which is one file read whole. */
    static final class Listens {
        /** By track urn. */
        final Map<String, Long> byUrn;
        private Map<String, Long> byImportedPath;

        private Listens(Map<String, Long> byUrn) {
            this.byUrn = byUrn;
        }

        static Listens read() {
            return new Listens(app.revanced.extension.soundcloud.player.ListeningStats.lastListens());
        }

        /** By file path, for imported tracks: their listens are kept under their urn, which holds the path. */
        Map<String, Long> byImportedPath() {
            if (byImportedPath == null) {
                Map<String, Long> paths = new java.util.HashMap<>();
                for (Map.Entry<String, Long> listen : byUrn.entrySet()) {
                    File file = LocalAdditions.importedFileOf(listen.getKey());
                    if (file != null) paths.put(file.getPath(), listen.getValue());
                }
                byImportedPath = paths;
            }
            return byImportedPath;
        }
    }

    private static final class UnplayedDownload extends Unplayed {
        private final String trackId;

        UnplayedDownload(String trackId, long size) {
            super(size);
            this.trackId = trackId;
        }

        @Override
        boolean isImported() {
            return false;
        }

        @Override
        boolean delete(Context context) {
            return DownloadTrackPatch.deleteDownload(context, trackId);
        }

        @Override
        long lastUseNow(SharedPreferences preferences, Listens listens) {
            DownloadIndex.Entry file = DownloadIndex.known(trackId);
            return lastUse(file == null ? 0 : file.modifiedMs(), preferences == null ? 0 : preferences.getLong(trackId, 0),
                    listens.byUrn.get(Urns.track(trackId)));
        }
    }

    private static final class UnplayedImport extends Unplayed {
        private final File file;

        UnplayedImport(File file, long size) {
            super(size);
            this.file = file;
        }

        @Override
        boolean isImported() {
            return true;
        }

        @Override
        boolean delete(Context context) {
            return LocalMusic.delete(file);
        }

        @Override
        long lastUseNow(SharedPreferences preferences, Listens listens) {
            return lastUse(file.lastModified(), preferences == null ? 0 : preferences.getLong(FILE_PREFIX + file.getPath(), 0),
                    listens.byImportedPath().get(file.getPath()));
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
        Listens history = Listens.read();
        Map<String, Long> listens = history.byUrn;

        for (String trackId : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (DownloadProgress.isAssembling(trackId)) continue;
            // Without a known file there is nothing to delete, and its age is unknown.
            DownloadIndex.Entry file = DownloadIndex.known(trackId);
            if (file == null) continue;
            long last = lastUse(file.modifiedMs(), preferences == null ? 0 : preferences.getLong(trackId, 0),
                    listens.get(Urns.track(trackId)));
            if (last < cutoff) result.add(new UnplayedDownload(trackId, file.size()));
        }

        Context context = Utils.getContext();
        if (isImportedIncluded() && context != null) {
            Map<String, Long> importedListens = history.byImportedPath();
            // The files alone: their size and time are all the rule needs, and reading the tags of each took a while.
            for (File file : LocalMusic.getFiles(context)) {
                if (RemovedTracks.isConvertedCopy(file)) continue;
                String path = file.getPath();
                // The copy is written at import, so its time is the import time.
                long last = lastUse(file.lastModified(),
                        preferences == null ? 0 : preferences.getLong(FILE_PREFIX + path, 0), importedListens.get(path));
                if (last < cutoff) result.add(new UnplayedImport(file, file.length()));
            }
        }
        return result;
    }

    /**
     * When the track was last used, in milliseconds: the latest of the time of its file, the time it started playing
     * here, and the time the listening statistics know.
     *
     * @param listenedAt The last listen from the statistics; null if there is none.
     */
    static long lastUse(long fileModified, long startedHere, Long listenedAt) {
        long last = Math.max(fileModified, startedHere);
        return listenedAt != null ? Math.max(last, listenedAt) : last;
    }

    /** Injection point: once per app start, on a background thread. */
    public static void runAtStart() {
        if (!isEnabled()) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            // The files of a chosen folder are known only once the first listing is done.
            DownloadIndex.awaitListing();
            int downloads = 0, imported = 0;
            long cutoff = System.currentTimeMillis() - days() * DAY_MS;
            SharedPreferences played = preferences();
            List<Unplayed> candidates = unplayed();
            // The listening history is one file read whole: read once for all the candidates, not once for each.
            Listens listens = candidates.isEmpty() ? null : Listens.read();
            for (Unplayed track : candidates) {
                // Started since the list was made (the clean-up runs a while after the start): it stays.
                if (track.lastUseNow(played, listens) >= cutoff) continue;
                if (!track.delete(context)) continue;
                if (track.isImported()) imported++;
                else downloads++;
            }
            int downloadCount = downloads, importedCount = imported;
            Logger.printInfo(() -> "Unplayed downloads: deleted " + downloadCount + " downloaded and " + importedCount
                    + " imported files not played for " + days() + " days");
            // Forget tracks that are gone.
            // Every start, not only after deletions: the file gets an entry for every track played.
            SharedPreferences preferences = preferences();
            if (preferences != null) {
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

    /** Written together at most every two seconds: every start of a track writes it. */
    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : app.revanced.extension.soundcloud.shared.BatchedPreferences.of(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }
}
