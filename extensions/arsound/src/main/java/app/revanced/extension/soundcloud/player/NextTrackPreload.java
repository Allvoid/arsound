package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.download.PrefetchedTracks;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Loads the next track of the queue whole while the current one plays, so it starts at once and does not stop on a
 * weak network. SoundCloud's own preload only fetches the first 1920 bytes of the next stream.
 * <p>
 * Only streamed tracks are loaded: a downloaded or imported next track already plays from the phone, so in a mixed
 * playlist only the tracks from the cloud cost data. On Wi-Fi the next track is loaded once the current one has played
 * for {@link #SETTLE_MS}; on mobile data only in the last {@link #METERED_LEAD_MS} of it, so skipping through a
 * playlist does not load every track. The copy is played like a download ({@link PrefetchedTracks}); copies of tracks
 * that are neither playing nor next are deleted.
 */
public final class NextTrackPreload {
    /** {@link Settings#PRELOAD_NEXT}: never, on Wi-Fi only, on any network. */
    public static final long OFF = 0, UNMETERED = 1, ALWAYS = 2;
    private static final String TRACK_URN = "soundcloud:tracks:";
    /** On Wi-Fi: how long the current track plays before the next one is loaded; quick skips load nothing. */
    private static final long SETTLE_MS = 8_000;
    /** On mobile data: how long before the end of the current track the next one is loaded. */
    private static final long METERED_LEAD_MS = 90_000;
    /** How often the queue is looked at while a track plays. */
    private static final long CHECK_EVERY_MS = 4_000;
    /** A failed or refused track is not asked for again for a while. */
    private static final long RETRY_MS = 5 * 60_000;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "arsound-next-track");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Object lock = new Object();
    private static boolean busy;
    private static long checkedAt;
    /** The track being loaded; a load stops at its next piece once this is another one. */
    private static String wanted;
    private static String failed;
    private static long failedAt;

    private NextTrackPreload() {
    }

    public static long mode() {
        return Settings.getLong(Settings.PRELOAD_NEXT, ALWAYS);
    }

    /** A position report of the player, about twice a second while it plays. The slow part runs on the worker. */
    static void onProgress(long position, long duration) {
        long mode = mode();
        if (mode == OFF || position < SETTLE_MS) return;
        Context context = Utils.getContext();
        if (context == null) return;
        if (isMetered(context) && (mode == UNMETERED || duration <= 0 || duration - position > METERED_LEAD_MS)) return;
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            if (busy || now - checkedAt < CHECK_EVERY_MS) return;
            busy = true;
            checkedAt = now;
        }
        worker.execute(NextTrackPreload::loadNext);
    }

    /** Another track starts: a load for a track that is no longer next stops, and the queue is looked at again. */
    static void onTrackChanged() {
        synchronized (lock) {
            wanted = null;
            checkedAt = 0;
        }
    }

    private static void loadNext() {
        String next = null;
        try {
            Context context = Utils.getContext();
            Object manager = PlayModeButton.queueManager();
            if (manager == null || context == null) return;
            String[] around = playingAndNext(manager);
            List<String> keep = new ArrayList<>(2);
            for (String id : around) if (id != null) keep.add(id);
            PrefetchedTracks.keepOnly(context, keep);
            next = around[1];
            if (next == null || DownloadTrackPatch.playableSource(next) != null) return;
            String id = next;
            synchronized (lock) {
                if (id.equals(failed) && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return;
                wanted = id;
            }
            Logger.printInfo(() -> "Loading the next track ahead: " + id);
            if (!PrefetchedTracks.fetch(context, id, () -> !id.equals(currentWanted()))) markFailed(id);
        } catch (Throwable ex) {
            String id = next;
            if (id != null) markFailed(id);
            Logger.printInfo(() -> "Next track " + id + " was not loaded ahead: " + ex);
        } finally {
            synchronized (lock) {
                busy = false;
            }
        }
    }

    private static String currentWanted() {
        synchronized (lock) {
            return wanted;
        }
    }

    private static void markFailed(String id) {
        synchronized (lock) {
            // A load stopped because the queue moved on is not a failure.
            if (!id.equals(wanted)) return;
            failed = id;
            failedAt = SystemClock.elapsedRealtime();
        }
    }

    /** The ids of the playing track and of the one that plays after it; null where that is not a SoundCloud track. */
    private static String[] playingAndNext(Object manager) throws Exception {
        Object queue = PlayQueueApi.queue(manager);
        int position = PlayQueueApi.position(manager);
        List<?> items = (List<?>) queue.getClass().getMethod("items").invoke(queue);
        int next = position + 1;
        String repeat = PlayQueueApi.repeatName(queue);
        if (PlayQueueApi.REPEAT_ONE.equals(repeat)) next = -1;
        else if (next >= items.size() && PlayQueueApi.REPEAT_ALL.equals(repeat)) next = 0;
        return new String[]{trackIdAt(items, position), trackIdAt(items, next)};
    }

    /**
     * The id of the queue item at a place if it is a track on SoundCloud; null for ads, imported files (their urns
     * are {@code sc-local:tracks:...}) and places outside the queue.
     */
    private static String trackIdAt(List<?> items, int index) throws Exception {
        if (index < 0 || index >= items.size()) return null;
        Object item = items.get(index);
        String urn = String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
        if (!urn.startsWith(TRACK_URN)) return null;
        String id = urn.substring(TRACK_URN.length());
        return id.matches("\\d+") ? id : null;
    }

    private static boolean isMetered(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        return connectivity == null || connectivity.isActiveNetworkMetered();
    }
}
