package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Which tracks are downloading right now, for the spinning download icon of SoundCloud.
 * <p>
 * The state comes from Android's download manager: one query per second at most, and only while
 * something is downloading and the app is on screen. When a download ends, the visible lists are
 * redrawn so the spinner turns into the "downloaded" icon.
 */
public final class DownloadProgress {
    private static final long POLL_MS = 1_500;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Guards the replacement of {@link #downloading} and {@link #assembling}: they are changed by several threads. */
    private static final Object SETS = new Object();

    /** Track ids currently downloading. Replaced as a whole, read from any thread. */
    private static volatile Set<String> downloading = Collections.emptySet();
    /** Track ids the app downloads and packs itself, which the download manager does not know about. */
    private static volatile Set<String> assembling = Collections.emptySet();
    /** Playlist id to the track ids started from its "Check track downloads" dialog. */
    private static final Map<String, Set<String>> playlistTracks = new HashMap<>();

    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean polling;
    /** Downloads that ended while the app was closed are settled at the first poll. */
    private static volatile boolean settledOnce;

    private DownloadProgress() {
    }

    public static boolean isDownloading(String trackId) {
        return trackId != null && downloading.contains(trackId);
    }

    public static boolean isPlaylistDownloading(String playlistId) {
        synchronized (playlistTracks) {
            Set<String> tracks = playlistTracks.get(playlistId);
            if (tracks == null) return false;
            for (String track : tracks) if (downloading.contains(track)) return true;
            return false;
        }
    }

    public static boolean isAssembling(String trackId) {
        return trackId != null && assembling.contains(trackId);
    }

    /**
     * Called when the app starts to download and pack an HLS track itself.
     *
     * @return False if the track is already being packed: two assemblies would write the same files.
     */
    static boolean tryBeginAssembly(String trackId, String playlistId) {
        synchronized (SETS) {
            if (assembling.contains(trackId)) return false;
            Set<String> updated = new HashSet<>(assembling);
            updated.add(trackId);
            assembling = updated;
        }
        onStarted(trackId, playlistId);
        return true;
    }

    /** Called when an HLS track is packed, or when packing failed. */
    static void onAssemblyFinished(String trackId) {
        synchronized (SETS) {
            Set<String> updated = new HashSet<>(assembling);
            updated.remove(trackId);
            assembling = updated;

            Set<String> stillDownloading = new HashSet<>(downloading);
            stillDownloading.remove(trackId);
            downloading = stillDownloading;
        }
        ProgressPill.onDownloadsChanged();
        recountAndRedraw();
    }

    /** Called when a download is queued. */
    static void onStarted(String trackId, String playlistId) {
        synchronized (SETS) {
            Set<String> updated = new HashSet<>(downloading);
            updated.add(trackId);
            downloading = updated;
        }
        if (playlistId != null) {
            synchronized (playlistTracks) {
                Set<String> tracks = playlistTracks.get(playlistId);
                if (tracks == null) playlistTracks.put(playlistId, tracks = new HashSet<>());
                tracks.add(trackId);
            }
        }
        // A playlist queues its tracks in a quick loop: one redraw covers all of them.
        if (!redrawQueued.compareAndSet(false, true)) return;
        handler.postDelayed(() -> {
            redrawQueued.set(false);
            redrawLists(resumed.get());
            startPolling();
        }, REDRAW_DELAY_MS);
    }

    private static final long REDRAW_DELAY_MS = 250;
    private static final java.util.concurrent.atomic.AtomicBoolean redrawQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Called when downloaded files were deleted, so the lists lose their "downloaded" icons. */
    static void onDownloadsDeleted() {
        recountAndRedraw();
    }

    /** Draws the lists on screen again, for example after new numbers were counted. */
    public static void redrawNow() {
        Utils.runOnMainThread(() -> redrawLists(resumed.get()));
    }

    /** The playlists count their tracks playing from a file again, then the lists are drawn again. */
    private static void recountAndRedraw() {
        Utils.runOnBackgroundThread(() -> {
            app.revanced.extension.soundcloud.local.PlaylistTracks.recountAll();
            DownloadsScreen.refresh();
            Utils.runOnMainThread(() -> redrawLists(resumed.get()));
        });
    }

    public static void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        startPolling();
    }

    public static void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }

    private static void startPolling() {
        if (polling || resumed.get() == null) return;
        polling = true;
        handler.postDelayed(DownloadProgress::poll, POLL_MS);
    }

    private static void poll() {
        polling = false;
        Activity activity = resumed.get();
        if (activity == null) return;
        Context context = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            Set<String> before = downloading;
            Set<String> current = queryDownloading(context);
            boolean changed;
            synchronized (SETS) {
                // Tracks packed by the app are not known to the download manager, so they are kept.
                current.addAll(assembling);
                // Tracks queued while the download manager was asked are not in its answer yet.
                for (String id : downloading) if (!before.contains(id)) current.add(id);
                changed = !current.equals(downloading);
                downloading = current;
            }
            // A finished download changes the numbers of files in the playlists; a failed one is forgotten.
            if (changed || !settledOnce) {
                settledOnce = true;
                DownloadTrackPatch.settleDownloads(context);
            }
            if (changed) {
                app.revanced.extension.soundcloud.local.PlaylistTracks.recountAll();
                DownloadsScreen.refresh();
            }
            Utils.runOnMainThread(() -> {
                if (changed) {
                    redrawLists(resumed.get());
                    ProgressPill.onDownloadsChanged();
                }
                if (!current.isEmpty()) startPolling();
            });
        });
    }

    private static Set<String> queryDownloading(Context context) {
        Set<String> result = new HashSet<>();
        Map<String, String> trackByFile = DownloadTrackPatch.trackIdsByFileName();
        if (trackByFile.isEmpty()) return result;
        try {
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query().setFilterByStatus(
                    DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_PAUSED);
            try (Cursor cursor = manager.query(query)) {
                int title = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                while (cursor.moveToNext()) {
                    String trackId = trackByFile.get(cursor.getString(title));
                    if (trackId != null) result.add(trackId);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check running downloads", ex);
        }
        return result;
    }

    /** Rebinds the rows of the lists on screen, so their download icons are built again. */
    private static void redrawLists(Activity activity) {
        if (activity == null) return;
        redraw(activity.getWindow().getDecorView());
    }

    private static void redraw(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals("androidx.recyclerview.widget.RecyclerView")) continue;
            try {
                Object adapter = type.getMethod("getAdapter").invoke(view);
                if (adapter != null) {
                    // Adapter.i() is notifyDataSetChanged in this app version.
                    Method notify = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false,
                            type.getClassLoader()).getMethod("i");
                    notify.invoke(adapter);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not redraw a list", ex);
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) redraw(group.getChildAt(i));
    }
}
