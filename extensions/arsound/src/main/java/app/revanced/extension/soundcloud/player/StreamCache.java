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
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static volatile boolean opened;

    private StreamCache() {
    }

    /** Injection point: whether the user chose a cache size. */
    public static boolean hasCustomSize() {
        return Settings.getLong(Settings.STREAM_CACHE_SIZE, 0) > 0;
    }

    /** Injection point: the cache size chosen by the user, in bytes. */
    public static long customSize() {
        return Settings.getLong(Settings.STREAM_CACHE_SIZE, 0);
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
            if (Settings.getBoolean(CLEAR_REQUESTED, false)) {
                int removed = removeOlderThan(directory, Long.MAX_VALUE);
                Settings.putBoolean(CLEAR_REQUESTED, false);
                Logger.printInfo(() -> "Stream cache cleared: " + removed + " files");
                return;
            }
            long days = Settings.getLong(Settings.STREAM_CACHE_DAYS, 0);
            int removed = days <= 0 ? 0 : removeOlderThan(directory, System.currentTimeMillis() - days * DAY_MS);
            Logger.printInfo(() -> "Stream cache opens, size " + (hasCustomSize() ? customSize() : "default")
                    + ", keep days " + days + ", removed " + removed + " parts");
        } catch (Exception ex) {
            Logger.printException(() -> "Stream cache cleanup failure", ex);
        }
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
