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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long REDRAW_DELAY_MS = 250;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Guards the replacement of {@link #downloading} and {@link #assembling}: they are changed by several threads. */
    private static final Object SETS = new Object();

    /** Track ids currently downloading. Replaced as a whole, read from any thread. */
    private static volatile Set<String> downloading = Collections.emptySet();
    /** Track ids the app downloads and packs itself, which the download manager does not know about. */
    private static volatile Set<String> assembling = Collections.emptySet();
    /**
     * Track ids waiting in or downloading through SoundCloud's own offline library (the DRM tracks). Only for telling
     * that something changed: the download manager knows nothing about them, and nothing else says when their icon
     * turns from the spinner into the downloaded one.
     */
    private static volatile Set<String> nativeActive = Collections.emptySet();
    /** Playlist id to the track ids started from its "Check track downloads" dialog. */
    private static final Map<String, Set<String>> playlistTracks = new HashMap<>();
    /** Set while a redraw waits for the rest of a playlist that is being queued. */
    private static final AtomicBoolean redrawQueued = new AtomicBoolean();
    /**
     * Set while a count of the playlists' downloaded tracks waits. A batch of downloads changes the set every few
     * seconds, and a count goes through every track of every playlist kept on the phone.
     */
    private static final AtomicBoolean recountQueued = new AtomicBoolean();
    /** A count is run at most this often; the last change of a batch is always counted. */
    private static final long RECOUNT_MS = 3_000;

    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean polling;
    /** Downloads that ended while the app was closed are settled at the first poll. */
    private static volatile boolean settledOnce;

    private DownloadProgress() {
    }

    /** A copy of the set with the track in it: the sets are replaced as a whole, never changed. */
    private static Set<String> with(Set<String> set, String trackId) {
        Set<String> updated = new HashSet<>(set);
        updated.add(trackId);
        return updated;
    }

    /** A copy of the set without the track. */
    private static Set<String> without(Set<String> set, String trackId) {
        Set<String> updated = new HashSet<>(set);
        updated.remove(trackId);
        return updated;
    }

    public static boolean isDownloading(String trackId) {
        return trackId != null && downloading.contains(trackId);
    }

    public static boolean isPlaylistDownloading(String playlistId) {
        Set<String> now = downloading;
        synchronized (playlistTracks) {
            Set<String> tracks = playlistTracks.get(playlistId);
            if (tracks == null || now.isEmpty()) return false;
            // The smaller set is walked: asked for on every playlist cell drawn.
            if (now.size() < tracks.size()) {
                for (String track : now) if (tracks.contains(track)) return true;
            } else {
                for (String track : tracks) if (now.contains(track)) return true;
            }
            return false;
        }
    }

    /** Lets go of the tracks of the playlists that are no longer downloading, and of playlists left with none. */
    private static void forgetFinishedPlaylistTracks() {
        Set<String> now = downloading;
        synchronized (playlistTracks) {
            java.util.Iterator<Set<String>> lists = playlistTracks.values().iterator();
            while (lists.hasNext()) {
                Set<String> tracks = lists.next();
                tracks.retainAll(now);
                if (tracks.isEmpty()) lists.remove();
            }
        }
    }

    public static boolean isAssembling(String trackId) {
        return trackId != null && assembling.contains(trackId);
    }

    /** Whether the app is downloading or packing any track itself right now. */
    static boolean isAnyAssembling() {
        return !assembling.isEmpty();
    }

    /**
     * Called when the app starts to download and pack an HLS track itself.
     *
     * @return False if the track is already being packed: two assemblies would write the same files.
     */
    static boolean tryBeginAssembly(String trackId, String playlistId) {
        synchronized (SETS) {
            if (assembling.contains(trackId)) return false;
            assembling = with(assembling, trackId);
        }
        onStarted(trackId, playlistId);
        return true;
    }

    /** Called when an HLS track is packed, or when packing failed. */
    static void onAssemblyFinished(String trackId) {
        synchronized (SETS) {
            assembling = without(assembling, trackId);
            downloading = without(downloading, trackId);
        }
        forgetFinishedPlaylistTracks();
        ProgressPill.onDownloadsChanged();
        // The icon turns at once; the numbers of the playlists follow with the recount.
        redrawSoon();
        recountAndRedraw();
    }

    /** Called when a download is queued. */
    static void onStarted(String trackId, String playlistId) {
        synchronized (SETS) {
            downloading = with(downloading, trackId);
        }
        if (playlistId != null) {
            synchronized (playlistTracks) {
                Set<String> tracks = playlistTracks.get(playlistId);
                if (tracks == null) playlistTracks.put(playlistId, tracks = new HashSet<>());
                tracks.add(trackId);
            }
        }
        redrawSoon();
    }

    /**
     * The lists are drawn again in a moment, and the polling of the running downloads starts. A playlist queues its
     * tracks in a quick loop: one redraw covers all of them.
     */
    static void redrawSoon() {
        if (!redrawQueued.compareAndSet(false, true)) return;
        handler.postDelayed(() -> {
            redrawQueued.set(false);
            redrawLists(resumed.get());
            startPolling();
        }, REDRAW_DELAY_MS);
    }

    /**
     * Called when a track was handed to SoundCloud's own offline download, or its request was taken back: its icon
     * changes, and the state of the library is followed from now on.
     */
    public static void onNativeChanged() {
        redrawSoon();
    }

    /** Called when downloaded files were deleted, so the lists lose their "downloaded" icons. */
    static void onDownloadsDeleted() {
        recountAndRedraw();
    }

    /**
     * Called when the files of the downloads changed without the app doing it: the media store was listed for the
     * first time, or a file was removed or added by something else. The lists ask for every track again.
     */
    static void onFilesChanged() {
        recountAndRedraw();
    }

    /** Draws the lists on screen again, for example after new numbers were counted. */
    public static void redrawNow() {
        Utils.runOnMainThread(() -> redrawLists(resumed.get()));
    }

    /**
     * The playlists count their tracks playing from a file again, then the lists are drawn again. Changes that come
     * close together are counted once, at most every {@link #RECOUNT_MS}.
     */
    private static void recountAndRedraw() {
        if (!recountQueued.compareAndSet(false, true)) return;
        handler.postDelayed(() -> Utils.runOnBackgroundThread(() -> {
            // Cleared first: a change made while this counts asks for another count.
            recountQueued.set(false);
            app.revanced.extension.soundcloud.local.PlaylistTracks.recountAll();
            DownloadsScreen.refresh();
            Utils.runOnMainThread(() -> redrawLists(resumed.get()));
        }), RECOUNT_MS);
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
            // SoundCloud's own offline library: the DRM tracks change their icon when its state of them does.
            Set<String> nativeNow = app.revanced.extension.soundcloud.offline.NativeOffline.inProgressIds();
            boolean nativeChanged = !nativeNow.equals(nativeActive);
            nativeActive = nativeNow;
            // A finished download changes the numbers of files in the playlists; a failed one is forgotten.
            if (changed || !settledOnce) {
                settledOnce = true;
                DownloadTrackPatch.settleDownloads(context);
            }
            if (changed) {
                forgetFinishedPlaylistTracks();
                // The icons of the tracks that started or ended change now; the numbers of the playlists follow.
                recountAndRedraw();
            }
            Utils.runOnMainThread(() -> {
                if (changed || nativeChanged) redrawLists(resumed.get());
                if (changed) ProgressPill.onDownloadsChanged();
                if (!current.isEmpty() || !nativeNow.isEmpty()) startPolling();
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

    private static volatile Method itemCount, rangeChanged;

    /**
     * The download state the cells of Compose lists (the search, the likes, a profile) were drawn from. Their rows are
     * not in a RecyclerView, so {@link #rebindRows} does not reach them: a cell that read this state while it drew its
     * icon ({@link #followedByCompose}) is composed again when the state changes ({@link #redrawCompose}).
     */
    private static final androidx.compose.runtime.MutableState<Integer> composeVersion = newComposeVersion();
    private static int composeCount;

    private static androidx.compose.runtime.MutableState<Integer> newComposeVersion() {
        try {
            return new androidx.compose.runtime.SnapshotMutableIntStateImpl(0);
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Compose lists are not told of download changes: " + ex);
            return null;
        }
    }

    /**
     * Called while a cell draws its download icon. Inside a composition it makes the cell follow the downloads: it is
     * composed again when they change. Anywhere else it does nothing.
     */
    public static void followedByCompose() {
        androidx.compose.runtime.MutableState<Integer> version = composeVersion;
        if (version != null) version.getValue();
    }

    private static void redrawCompose() {
        androidx.compose.runtime.MutableState<Integer> version = composeVersion;
        if (version == null) return;
        try {
            version.setValue(++composeCount);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not tell the Compose lists of a download change", ex);
        }
    }

    /**
     * Rebinds every row of the list in its place: {@code notifyItemRangeChanged(0, count, payload)}. Not
     * {@code notifyDataSetChanged}: that one throws the rows away, they were built again empty for a frame and took
     * their height back one by one, so the tracks of a playlist jumped about and the list fell back to its top while
     * a download started or ended. With a payload the same row is bound again, without the fade of a changed row.
     * The names of RecyclerView's methods are shrunk; they are told by their shape.
     */
    private static void rebindRows(Object adapter, ClassLoader loader) throws Exception {
        if (rangeChanged == null) {
            Class<?> adapterClass = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, loader);
            for (Method method : adapterClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                boolean isAbstract = java.lang.reflect.Modifier.isAbstract(method.getModifiers());
                if (isAbstract && parameters.length == 0 && method.getReturnType() == int.class) itemCount = method;
                if (parameters.length == 3 && parameters[0] == int.class && parameters[1] == int.class
                        && parameters[2] == Object.class && method.getReturnType() == void.class) rangeChanged = method;
            }
            if (itemCount == null || rangeChanged == null) throw new NoSuchMethodException("RecyclerView.Adapter");
        }
        int count = (int) itemCount.invoke(adapter);
        if (count > 0) rangeChanged.invoke(adapter, 0, count, REBIND);
    }

    /** The payload of {@link #rebindRows}: any object makes the list keep the row and bind it again. */
    private static final Object REBIND = "arsound-download-state";

    /** Rebinds the rows of the lists on screen, so their download icons are built again. */
    private static void redrawLists(Activity activity) {
        redrawCompose();
        if (activity == null) return;
        redraw(activity.getWindow().getDecorView());
    }

    private static void redraw(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals("androidx.recyclerview.widget.RecyclerView")) continue;
            try {
                Object adapter = type.getMethod("getAdapter").invoke(view);
                if (adapter != null) rebindRows(adapter, type.getClassLoader());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not redraw a list", ex);
            }
            // A row may hold a list of its own (the sideways rows of the home screen): it is gone through too.
            break;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) redraw(group.getChildAt(i));
    }
}
