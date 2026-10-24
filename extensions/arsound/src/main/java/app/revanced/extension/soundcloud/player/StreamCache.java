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
            // Not measured yet (the first start with this): the chosen size stays.
            long available = Settings.getLong(AVAILABLE_BYTES, -1);
            if (available < 0) return chosen;
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
                int removed = clear(directory, trash);
                Settings.putBoolean(CLEAR_REQUESTED, false);
                Logger.printInfo(() -> "Stream cache cleared: " + removed + " files deleted at once, the rest in the background");
            } else {
                long days = Settings.getLong(Settings.STREAM_CACHE_DAYS, 0);
                int removed = days <= 0 ? 0 : removeOlderThan(directory, System.currentTimeMillis() - days * DAY_MS);
                Logger.printInfo(() -> "Stream cache opens, size " + (hasCustomSize() ? customSize() : "default")
                        + ", keep days " + days + ", removed " + removed + " parts");
            }
            // Also what an earlier run did not finish deleting.
            deleteInBackground(trash);
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
     * Empties the cache. This runs while SoundCloud waits for the cache to open, and deleting
     * thousands of parts takes seconds, so each part folder is moved out of the cache with one
     * rename and deleted later in the background. A folder that cannot be moved is emptied here.
     * The index files of the cache stay.
     *
     * @return The number of parts deleted here.
     */
    private static int clear(File directory, File trash) {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        boolean trashReady = trash != null && (trash.isDirectory() || trash.mkdirs());
        int removed = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                if (trashReady && file.renameTo(new File(trash, System.nanoTime() + "-" + file.getName()))) continue;
                removed += removeOlderThan(file, Long.MAX_VALUE);
            } else if (file.getName().endsWith(".exo") && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /** Low priority: the playback that started the cache must not compete with the deletion. */
    private static void deleteInBackground(File trash) {
        if (trash == null || !trash.exists()) return;
        Thread thread = new Thread(() -> {
            try {
                int deleted = deleteAll(trash);
                Logger.printInfo(() -> "Stream cache trash emptied: " + deleted + " files");
            } catch (Throwable t) {
                Logger.printException(() -> "Could not empty the stream cache trash", t);
            }
        }, "arsound-cache-trash");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    private static int deleteAll(File file) {
        int removed = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) removed += deleteAll(child);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } else if (file.delete()) {
            removed++;
        }
        return removed;
    }

    /** Counts the cache for {@link #customSize()} of the next start: counting walks every part. */
    private static void measureInBackground(File directory) {
        Thread thread = new Thread(() -> {
            try {
                long used = size(directory);
                Settings.putLong(AVAILABLE_BYTES, used + directory.getUsableSpace());
            } catch (Throwable t) {
                Logger.printException(() -> "Could not measure the stream cache", t);
            }
        }, "arsound-cache-size");
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
        int removed = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                removed += removeOlderThan(file, before);
            } else if (file.getName().endsWith(".exo") && lastUse(file) < before && file.delete()) {
                removed++;
            }
        }
        return removed;
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
        return size(directory());
    }

    private static long size(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] files = file.listFiles();
        if (files != null) for (File child : files) total += size(child);
        return total;
    }
}
