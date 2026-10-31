package app.revanced.extension.soundcloud.player;

import java.io.File;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * SoundCloud keeps the parts of streamed tracks in a cache of 120 MB (500 MB outside ad countries)
 * and keeps them until the cache is full. Here the size is chosen by the user, parts unused for a
 * number of days are removed, and the whole cache can be cleared.
 * <p>
 * Files are only removed before SoundCloud opens the cache: the cache reads its folder when it
 * opens and forgets the removed parts, while a file removed from an open cache would break the
 * track that uses it.
 */
@SuppressWarnings("unused")
public final class StreamCache {
    /** Set by "Clear cache": the cache is emptied the next time SoundCloud opens it. */
    public static final String CLEAR_REQUESTED = "stream_cache_clear";
    /**
     * The size of the cache plus the free space, measured together: the cache only takes space from the free
     * space, so their sum is what the cache can grow to, whatever it held when it was measured.
     */
    private static final String AVAILABLE_BYTES = "stream_cache_available";
    /** The size of the cache as last counted, in bytes; the free space beside it is read when the cache opens. */
    private static final String USED_BYTES = "stream_cache_used";
    /** Next to the cache folder, not inside it: the cache would read it. */
    private static final String TRASH = "exocache-trash";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** SoundCloud's own smallest cache. */
    private static final long MIN_SIZE = 120L * 1024 * 1024;
    /** Free space left for other apps and for downloads. */
    private static final long FREE_SPACE_RESERVE = 1024L * 1024 * 1024;
    private static volatile boolean opened;

    private StreamCache() {
    }

    /** Injection point: whether the user chose a cache size. */
    public static boolean hasCustomSize() {
        return Settings.getLong(Settings.STREAM_CACHE_SIZE, 0) > 0;
    }

    /**
     * Injection point: the cache size chosen by the user, in bytes. Not more than the cache and the
     * free space together allow: a cache that fills the storage makes playback fail on every part
     * it cannot write, and stays that way until it is cleared.
     */
    public static long customSize() {
        long chosen = Settings.getLong(Settings.STREAM_CACHE_SIZE, 0);
        if (chosen <= 0) return chosen;
        try {
            // The free space now (one call to the file system), with the cache as last counted (that count walks
            // every part, so it is done in the background): the space of the last start could be long gone.
            File directory = directory();
            long free = directory == null ? -1 : (directory.isDirectory() ? directory : directory.getParentFile()).getUsableSpace();
            if (free <= 0) {
                long available = Settings.getLong(AVAILABLE_BYTES, -1);
                if (available < 0) return chosen;
                free = available;
            } else {
                free += Math.max(0, Settings.getLong(USED_BYTES, 0));
            }
            long available = free;
            long limit = Math.max(MIN_SIZE, available - FREE_SPACE_RESERVE);
            if (limit < chosen) Logger.printInfo(() -> "Stream cache size " + chosen + " is limited by free space to " + limit);
            return Math.min(chosen, limit);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not limit the stream cache by free space", ex);
            return chosen;
        }
    }

    /**
     * Injection point, called before SoundCloud opens its stream cache.
     *
     * @param directory The cache folder.
     */
    public static void beforeOpen(File directory) {
        if (opened) return;
        opened = true;
        try {
            if (directory == null || !directory.isDirectory()) return;
            File trash = trashOf(directory);
            if (Settings.getBoolean(CLEAR_REQUESTED, false)) {
                boolean moved = clear(directory, trash);
                Settings.putBoolean(CLEAR_REQUESTED, false);
                Logger.printInfo(() -> "Stream cache cleared: " + (moved ? "moved to the trash at once" : "could not be moved"));
                deleteInBackground(trash);
            } else {
                long days = Settings.getLong(Settings.STREAM_CACHE_DAYS, 0);
                Logger.printInfo(() -> "Stream cache opens, size " + (hasCustomSize() ? customSize() : "default")
                        + ", keep days " + days);
                // The old parts are found and moved to the trash in the background, not while SoundCloud waits for the
                // cache: walking thousands of parts took seconds. A part moved away under the open cache is one it
                // forgets when it misses it, and these were not played for days.
                if (days > 0) removeOldInBackground(directory, trash, System.currentTimeMillis() - days * DAY_MS);
                // Also what an earlier run did not finish deleting.
                else deleteInBackground(trash);
            }
            if (hasCustomSize()) measureInBackground(directory);
        } catch (Exception ex) {
            Logger.printException(() -> "Stream cache cleanup failure", ex);
        }
    }

    private static File trashOf(File directory) {
        File parent = directory.getParentFile();
        return parent == null ? null : new File(parent, TRASH);
    }

    /**
     * Empties the cache. This runs while SoundCloud waits for the cache to open, so nothing is deleted here: the whole
     * folder, parts and index, is moved to the trash with one rename and an empty one takes its place; the cache starts
     * anew, and the trash is emptied in the background.
     *
     * @return Whether the folder was moved.
     */
    private static boolean clear(File directory, File trash) {
        if (trash == null || !(trash.isDirectory() || trash.mkdirs())) return false;
        if (!directory.renameTo(new File(trash, System.nanoTime() + "-" + directory.getName()))) return false;
        if (!directory.mkdirs()) Logger.printInfo(() -> "Stream cache: could not make the empty folder again");
        return true;
    }

    /** Deletes the parts last used before the time, and what is left in the trash, off the opening of the cache. */
    private static void removeOldInBackground(File directory, File trash, long before) {
        runInBackground("arsound-cache-old", () -> {
            try {
                int removed = removeOlderThan(directory, before);
                Logger.printInfo(() -> "Stream cache: " + removed + " old parts removed");
                if (trash != null && trash.exists()) FileTree.deleteAll(trash);
            } catch (Throwable t) {
                Logger.printException(() -> "Could not remove the old parts of the stream cache", t);
            }
        });
    }

    /** Empties the trash, also what an earlier run did not finish deleting. */
    private static void deleteInBackground(File trash) {
        if (trash == null || !trash.exists()) return;
        runInBackground("arsound-cache-trash", () -> {
            try {
                int deleted = FileTree.deleteAll(trash);
                Logger.printInfo(() -> "Stream cache trash emptied: " + deleted + " files");
            } catch (Throwable t) {
                Logger.printException(() -> "Could not empty the stream cache trash", t);
            }
        });
    }

    /** Counts the cache for {@link #customSize()} of the next start: counting walks every part. */
    private static void measureInBackground(File directory) {
        runInBackground("arsound-cache-size", () -> {
            try {
                long used = FileTree.size(directory);
                Settings.putLong(USED_BYTES, used);
                Settings.putLong(AVAILABLE_BYTES, used + directory.getUsableSpace());
            } catch (Throwable t) {
                Logger.printException(() -> "Could not measure the stream cache", t);
            }
        });
    }

    /** Low priority: the playback that started the cache must not compete with the work. */
    private static void runInBackground(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Removes cached parts last used before the given time. Their names end with the time of the
     * last use: {@code id.position.time.v3.exo}. The index files of the cache stay.
     *
     * @param before {@link Long#MAX_VALUE} removes every part.
     */
    private static int removeOlderThan(File directory, long before) {
        return FileTree.deleteFiles(directory, file -> file.getName().endsWith(".exo") && lastUse(file) < before);
    }

    private static long lastUse(File part) {
        String[] pieces = part.getName().split("\\.");
        long time = part.lastModified();
        if (pieces.length >= 5) {
            try {
                time = Math.max(time, Long.parseLong(pieces[pieces.length - 3]));
            } catch (NumberFormatException ignored) {
            }
        }
        return time;
    }

    /** The folder SoundCloud keeps the cache in. */
    public static File directory() {
        android.content.Context context = Utils.getContext();
        if (context == null) return null;
        File base = context.getExternalFilesDir(null);
        return new File(base != null ? base : context.getFilesDir(), "exocache");
    }

    /** Space taken by the cache, in bytes. Blocks. */
    public static long usedBytes() {
        return FileTree.size(directory());
    }
}
