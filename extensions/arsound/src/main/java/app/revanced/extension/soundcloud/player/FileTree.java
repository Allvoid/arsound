package app.revanced.extension.soundcloud.player;

import java.io.File;
import java.util.function.Predicate;

/** Walks a folder and everything below it: the cache folders are trees of part files. */
final class FileTree {
    private FileTree() {
    }

    /** The bytes of the file, or of all the files below the folder; 0 for something that does not exist. */
    static long size(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] files = file.listFiles();
        if (files != null) for (File child : files) total += size(child);
        return total;
    }

    /**
     * Deletes the files below the folder that the filter picks; the folders stay.
     *
     * @return The number of files deleted.
     */
    static int deleteFiles(File folder, Predicate<File> filter) {
        int removed = 0;
        File[] files = folder.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                removed += deleteFiles(file, filter);
            } else if (filter.test(file) && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Deletes the file, or the folder with everything in it.
     *
     * @return The number of files deleted (folders are not counted).
     */
    static int deleteAll(File file) {
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
}
