package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Loading the tracks of imported files from SoundCloud's track repository, which reads the tags of the files.
 * <p>
 * Reading is slow, so a track is asked for within a short budget, and what is still missing is loaded one file
 * at a time in the background. The loaded tracks are cached; playlists are told to reload when a batch is done.
 */
final class LocalTrackLoader {
    private static final Map<String, Object> LOCAL_TRACK_CACHE = new ConcurrentHashMap<>();
    /** Files queued for or in a background load: a list refresh does not queue them again. */
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    /** When a file last failed to load in the background, so a file that never loads is not retried at every refresh. */
    private static final Map<String, Long> LOAD_FAILED_AT = new ConcurrentHashMap<>();
    /** The file behind each cache key, so one changed file can be forgotten without reloading all the others. */
    private static final Map<String, LoadedFile> FILES = new ConcurrentHashMap<>();

    /** An imported file as it was when its track was read. */
    private static final class LoadedFile {
        final File file;
        final long modified;

        LoadedFile(File file) {
            this.file = file;
            this.modified = file.lastModified();
        }

        /** True if the file is gone or was written again since its tags were read. */
        boolean isStale() {
            return !file.exists() || file.lastModified() != modified;
        }
    }
    private static final long LOAD_RETRY_MS = 30_000;
    private static final long BACKGROUND_TIMEOUT_MS = 5_000;
    /**
     * SoundCloud reads the metadata of all files with one shared MediaMetadataRetriever, which breaks when
     * loads overlap (wrong or garbled metadata, a native abort), so files load one at a time. Fair, so the
     * list refresh waiting within its budget is not starved by the background loads.
     */
    /** One read of a file's tags at a time, held until the read has really ended. */
    private static final java.util.concurrent.Semaphore READ_SLOT = new java.util.concurrent.Semaphore(1, true);
    /** The files whose read runs now. */
    private static final Set<String> IN_FLIGHT = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** A read is taken for ended after this long, whatever its source said. */
    private static final long MAX_READ_MS = 60_000;
    /** Files loaded in the background since the playlists were last told to reload. */
    private static final AtomicInteger LOADED_SINCE_REFRESH = new AtomicInteger();
    private static final int REFRESH_EVERY = 100;
    /** One thread of its own: the background pool of Utils starts a new thread for every task over three. */
    private static final ThreadPoolExecutor LOCAL_LOADER = createLoader();

    private LocalTrackLoader() {
    }

    private static ThreadPoolExecutor createLoader() {
        ThreadPoolExecutor loader = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "arsound-local-track");
            thread.setDaemon(true);
            return thread;
        });
        loader.allowCoreThreadTimeOut(true);
        return loader;
    }

    /** The {@code LoadStrategy} enum of SoundCloud's repositories. */
    static Class<?> strategyClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("com.soundcloud.android.foundation.domain.repository.LoadStrategy", false, loader);
    }

    /** {@code LocalFileAwareTrackRepository.track(TrackUrn, LoadStrategy)}, which loads one track. */
    static Method trackMethod(Object repository) throws Exception {
        ClassLoader loader = repository.getClass().getClassLoader();
        Class<?> trackUrnClass = Class.forName("com.soundcloud.android.foundation.domain.TrackUrn", false, loader);
        return repository.getClass().getMethod("track", trackUrnClass, strategyClass(loader));
    }

    /** The loaded track of an imported file, or null if it is not loaded yet. */
    static Object cached(Object urn) {
        return LOCAL_TRACK_CACHE.get(String.valueOf(urn));
    }

    /** True once the track of an imported file is loaded, so a list can draw it right away. */
    static boolean isLoaded(Object urn) {
        return LOCAL_TRACK_CACHE.containsKey(String.valueOf(urn));
    }

    /** Called when many imported files change at once, so no file is shown from a stale cache entry. */
    static void clearCache() {
        LOCAL_TRACK_CACHE.clear();
        LOAD_FAILED_AT.clear();
        FILES.clear();
    }

    /**
     * Drops the cached track of one imported file (deleted, or its cover changed). The other files stay loaded, so
     * lists that show them (the likes, the imported playlist) do not empty out and fill again one file at a time.
     */
    static void forget(File file) {
        for (Map.Entry<String, LoadedFile> entry : FILES.entrySet()) {
            if (entry.getValue().file.equals(file)) forgetKey(entry.getKey());
        }
        // A file that failed to load is not in FILES; a change to the files is a reason to try it again at once.
        LOAD_FAILED_AT.clear();
    }

    /**
     * Drops the cached tracks of files deleted or replaced outside Arsound (a file manager), so a new file that got
     * the same path is read again. Called when files are added.
     */
    static void forgetStale() {
        for (Map.Entry<String, LoadedFile> entry : FILES.entrySet()) {
            if (entry.getValue().isStale()) forgetKey(entry.getKey());
        }
        LOAD_FAILED_AT.clear();
    }

    private static void forgetKey(String key) {
        LOCAL_TRACK_CACHE.remove(key);
        LOAD_FAILED_AT.remove(key);
        FILES.remove(key);
    }

    /**
     * Loads the track of an imported file and waits for it.
     *
     * @return The track, or null if it did not load within the timeout or another file is being read.
     */
    static Object loadWithin(Object repository, Method track, Object urn, Object strategy, long timeoutMs) {
        boolean locked = false;
        String key = String.valueOf(urn);
        // The slot is given back when the read really ends: a wait that ran out leaves the native read going, and a
        // second read beside it shared SoundCloud's one metadata reader.
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            IN_FLIGHT.remove(key);
            READ_SLOT.release();
        };
        try {
            long started = System.nanoTime();
            locked = READ_SLOT.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                Logger.printInfo(() -> "Local file track not loaded, another file is loading: " + urn);
                return null;
            }
            IN_FLIGHT.add(key);
            // A source that never answers must not keep the slot for ever.
            Utils.runOnMainThreadDelayed(release, MAX_READ_MS);
            // The time spent waiting for the slot counts against the timeout.
            long left = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (left <= 0) {
                release.run();
                return null;
            }
            Object found = Rx.firstWithin(track.invoke(repository, urn, strategy), left, TimeUnit.MILLISECONDS, release);
            Object item = found == null ? null : found.getClass().getMethod("getItem").invoke(found);
            if (item != null) {
                if (Urns.isLocalFile(urn)) FILES.put(key, new LoadedFile(Urns.fileOf(urn)));
                LOCAL_TRACK_CACHE.put(key, item);
            }
            return item;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (locked) release.run();
            return null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Local file track unavailable: " + urn + " " + ex);
            if (locked) release.run();
            return null;
        }
    }

    /**
     * Loads the tracks of imported files one after another in the background, for a list that is not read through
     * {@link LocalFileTracks#withLocalFiles}. It is redrawn when they are done (see {@link #refreshAfterBackgroundLoads}).
     *
     * @param repository The {@code LocalFileAwareTrackRepository}.
     * @param urns       The {@code LocalTrackUrn}s.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void queueAll(Object repository, List<Object> urns) {
        try {
            Method track = trackMethod(repository);
            Object strategy = Enum.valueOf((Class) strategyClass(repository.getClass().getClassLoader()), "SYNC_MISSING");
            for (Object urn : urns) queue(repository, track, urn, strategy);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not load local file tracks", ex);
        }
    }

    static void queue(Object repository, Method track, Object urn, Object strategy) {
        String key = String.valueOf(urn);
        // Being read right now (by a list that ran out of time): the read in flight brings it.
        if (LOCAL_TRACK_CACHE.containsKey(key) || IN_FLIGHT.contains(key)) return;
        Long failedAt = LOAD_FAILED_AT.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < LOAD_RETRY_MS) return;
        if (!LOADING.add(key)) return;
        try {
            LOCAL_LOADER.execute(() -> loadQueued(repository, track, urn, strategy, key));
        } catch (RejectedExecutionException | OutOfMemoryError ex) {
            // No thread could be started: the file is queued again by a later refresh.
            LOADING.remove(key);
            Logger.printInfo(() -> "Could not queue local file track " + key + ": " + ex);
        }
    }

    /** Runs on the loader thread. */
    private static void loadQueued(Object repository, Method track, Object urn, Object strategy, String key) {
        try {
            // The list refresh may have loaded it within its budget while this one waited in the queue.
            if (!LOCAL_TRACK_CACHE.containsKey(key)) {
                if (loadWithin(repository, track, urn, strategy, BACKGROUND_TIMEOUT_MS) != null) {
                    LOAD_FAILED_AT.remove(key);
                    LOADED_SINCE_REFRESH.incrementAndGet();
                } else {
                    LOAD_FAILED_AT.put(key, System.currentTimeMillis());
                }
            }
        } catch (Throwable ex) {
            // An error escaping a pool thread would reach the default handler and close the app.
            Logger.printException(() -> "Could not load local file track " + key, ex);
        } finally {
            LOADING.remove(key);
        }
        refreshAfterBackgroundLoads();
    }

    /**
     * Files missing at the first read of a list are loaded afterwards. Once a batch is done (or every
     * {@link #REFRESH_EVERY} files of a long one), the playlists reload their tracks, so the loaded files
     * show up without reopening the screen. Only loaded files trigger it, so files that never load cannot repeat it.
     */
    private static void refreshAfterBackgroundLoads() {
        int loaded = LOADED_SINCE_REFRESH.get();
        if (loaded == 0 || (loaded < REFRESH_EVERY && !LOADING.isEmpty())) return;
        LOADED_SINCE_REFRESH.addAndGet(-loaded);
        // The likes list waits for the files it left out until they were loaded.
        ImportedLikes.refresh();
        try {
            Set<String> playlists = new LinkedHashSet<>(PlaylistTracks.playlistsWithImportedTracks());
            String saved = SavedPlaylist.getUrn();
            if (saved != null) playlists.add(saved);
            Utils.runOnMainThread(() -> {
                for (String playlist : playlists) PlaylistChanges.playlistChanged(playlist);
            });
        } catch (Throwable ex) {
            // Runs on the loader thread, where an error would close the app.
            Logger.printException(() -> "Could not refresh the playlists after loading local files", ex);
        }
    }
}
