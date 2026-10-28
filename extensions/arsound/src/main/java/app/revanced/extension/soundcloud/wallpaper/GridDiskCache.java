package app.revanced.extension.soundcloud.wallpaper;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * A folder of small JPEG files that keeps the sharp cells of the Wallhaven grid, so scrolling back and opening the
 * screen again cost nothing. When the folder grows over its limit the files used longest ago are removed first.
 * Plain files, no Android, so it is tested on the JVM.
 */
final class GridDiskCache {
    /** The folder is looked through for old files after this many new ones. */
    private static final int TRIM_EVERY = 16;
    /** Trimming goes down to this part of the limit, so it does not run again after every file. */
    private static final double TRIM_TO = 0.9;

    private final File folder;
    private final long maxBytes;
    private int sinceTrim = TRIM_EVERY;

    GridDiskCache(File folder, long maxBytes) {
        this.folder = folder;
        this.maxBytes = maxBytes;
    }

    /** The file of this key if it is kept; it counts as used just now. Null if there is none. */
    synchronized File get(String key) {
        File file = fileOf(key);
        if (!file.isFile()) return null;
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
        return file;
    }

    /** Keeps the bytes under this key, whole or not at all. */
    synchronized void put(String key, byte[] bytes) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        File file = fileOf(key);
        AtomicFiles.write(new File(folder, file.getName() + ".tmp"), file, bytes);
        if (++sinceTrim >= TRIM_EVERY) {
            sinceTrim = 0;
            trim();
        }
    }

    /** Removes the files used longest ago until the folder is under its limit. */
    synchronized void trim() {
        File[] files = folder.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long goal = (long) (maxBytes * TRIM_TO);
        for (File file : files) {
            if (total <= goal) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    /** The key with every character that a file name could not take replaced, so it cannot leave the folder. */
    static String safeName(String key) {
        StringBuilder name = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean plain = c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' || c == '_';
            name.append(plain ? c : '_');
        }
        return name.append(".jpg").toString();
    }

    private File fileOf(String key) {
        return new File(folder, safeName(key));
    }
}
