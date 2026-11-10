package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.recommendations.WaveBatch;

/**
 * "Download" of the "My Wave" tab: downloads the tracks the list holds at the moment of the tap. The tracks are fixed
 * then ({@link WaveBatch#snapshot}); the wave adds tracks often, and those that come later are not downloaded. One
 * run at a time: a tap while a run goes on starts nothing. Tracks already on the phone are not downloaded again,
 * protected ones go to SoundCloud's own offline library as with any playlist, and the files go through the ordinary
 * download queue, so the network and the disk are not loaded more than a playlist download does.
 * <p>
 * The run starts the tracks one by one (the API is asked with a pause between requests), then watches them until
 * they are on the phone and tells the result. {@link #progress()} says how far it is, for the ring on the button.
 */
public final class WaveDownloads {
    /** A run that watches its downloads gives up after this long. */
    private static final long WATCH_LIMIT_MS = 20 * 60_000L;
    private static final long WATCH_STEP_MS = 1_500;

    private static volatile boolean running;
    private static volatile int total;
    private static volatile int finished;

    private WaveDownloads() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** How far the run is, 0 to 1, or -1 when none runs. */
    public static float progress() {
        return running ? WaveBatch.fraction(finished, total) : -1f;
    }

    /**
     * @param urns The snapshot of the list, taken at the tap.
     * @return False when a run is already going on.
     */
    public static synchronized boolean start(Context context, List<String> urns) {
        if (running) return false;
        List<String> ids = new ArrayList<>();
        for (String urn : urns) ids.add(WaveBatch.trackId(urn));
        Context app = context.getApplicationContext();
        total = ids.size();
        finished = 0;
        running = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                run(app, ids);
            } catch (Exception ex) {
                Logger.printException(() -> "My Wave: the download of the list failed", ex);
                DownloadTrackPatch.showToast(app, text("Скачивание списка не удалось", "Could not download the list"));
            } finally {
                running = false;
            }
        });
        return true;
    }

    private static void run(Context context, List<String> ids) throws InterruptedException {
        int already = 0, offline = 0, unavailable = 0, failed = 0;
        List<String> watched = new ArrayList<>();
        for (String id : ids) {
            DownloadTrackPatch.DownloadState state = DownloadTrackPatch.getDownloadState(context, id);
            if (state == DownloadTrackPatch.DownloadState.DOWNLOADED) {
                already++;
                finished++;
                continue;
            }
            if (state == DownloadTrackPatch.DownloadState.IN_PROGRESS) {
                watched.add(id);
                continue;
            }
            try {
                TrackSource source = DownloadTrackPatch.resolveSource(id);
                if (!source.isDownloadable() && !DownloadTrackPatch.goesToNativeOffline(source.status)) {
                    unavailable++;
                    finished++;
                    continue;
                }
                if (DownloadTrackPatch.goesToNativeOffline(source.status)) offline++;
                if (DownloadTrackPatch.downloadSilently(context, id, source, null)) watched.add(id);
                else {
                    unavailable++;
                    finished++;
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "My Wave: the track " + id + " did not start: " + ex);
                failed++;
                finished++;
            }
        }
        // The downloads run on their own; here they are watched, so the button shows the progress and the result is told.
        long until = SystemClock.elapsedRealtime() + WATCH_LIMIT_MS;
        List<String> waiting = new ArrayList<>(watched);
        int done = 0;
        while (!waiting.isEmpty() && SystemClock.elapsedRealtime() < until) {
            for (int i = waiting.size() - 1; i >= 0; i--) {
                DownloadTrackPatch.DownloadState state = DownloadTrackPatch.getDownloadState(context, waiting.get(i));
                if (state == DownloadTrackPatch.DownloadState.IN_PROGRESS) continue;
                if (state == DownloadTrackPatch.DownloadState.DOWNLOADED) done++;
                else failed++;
                waiting.remove(i);
                finished++;
            }
            if (!waiting.isEmpty()) Thread.sleep(WATCH_STEP_MS);
        }
        failed += waiting.size();
        String summary = WaveBatch.downloadSummary(done - offline < 0 ? 0 : done - offline, already, offline, unavailable, failed,
                ids.size());
        Logger.printInfo(() -> "My Wave: download of the list: " + summary);
        DownloadTrackPatch.showToast(context, summary);
    }
}
