package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Deletes the files of downloaded tracks nobody listened to for a while (Settings → Arsound → Storage), off by
 * default. The track itself stays in SoundCloud: it plays from the network and can be downloaded again.
 * <p>
 * A track counts as listened to when it starts playing (recorded here since this feature exists; before that, the
 * listening statistics know). A track never played counts from its download. The check runs once per app start.
 */
public final class UnplayedDownloads {
    public static final String ENABLED = "downloads_unplayed_cleanup";
    public static final String DAYS = "downloads_unplayed_days";
    public static final long DEFAULT_DAYS = 30;
    public static final long MAX_DAYS = 365;

    private static final String PREFERENCES_NAME = "arsound_played_downloads";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private UnplayedDownloads() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, false);
    }

    public static long days() {
        return Math.max(1, Math.min(MAX_DAYS, Settings.getLong(DAYS, DEFAULT_DAYS)));
    }

    /** Called when a track starts playing. Only downloaded tracks are remembered. */
    public static void onTrackStarted(String trackUrn) {
        String trackId = DownloadTrackPatch.parseTrackId(trackUrn);
        if (trackId == null || !DownloadTrackPatch.isDownloaded(trackId)) return;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putLong(trackId, System.currentTimeMillis()).apply();
    }

    /** The downloaded tracks that would be deleted now: track id and file. */
    public static List<Object[]> unplayed() {
        List<Object[]> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - days() * DAY_MS;
        SharedPreferences preferences = preferences();
        Map<String, Long> listens = app.revanced.extension.soundcloud.player.ListeningStats.lastListens();
        for (String trackId : DownloadTrackPatch.getDownloadedTrackIds()) {
            if (DownloadProgress.isAssembling(trackId)) continue;
            // Without a known file there is nothing to delete, and its age is unknown.
            File file = DownloadTrackPatch.knownFile(trackId);
            if (file == null) continue;
            long last = file.lastModified();
            if (preferences != null) last = Math.max(last, preferences.getLong(trackId, 0));
            Long listened = listens.get(trackId);
            if (listened != null) last = Math.max(last, listened);
            if (last < cutoff) result.add(new Object[]{trackId, file});
        }
        return result;
    }

    /** Injection point: once per app start, on a background thread. */
    public static void runAtStart() {
        if (!isEnabled()) return;
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            int deleted = 0;
            for (Object[] track : unplayed()) {
                if (DownloadTrackPatch.deleteDownload(context, (String) track[0])) deleted++;
            }
            SharedPreferences preferences = preferences();
            int count = deleted;
            Logger.printInfo(() -> "Unplayed downloads: deleted " + count + " files not played for " + days() + " days");
            // Forget tracks that are no longer downloaded.
            if (preferences != null && count > 0) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String trackId : preferences.getAll().keySet()) {
                    if (!DownloadTrackPatch.isDownloaded(trackId)) editor.remove(trackId);
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
