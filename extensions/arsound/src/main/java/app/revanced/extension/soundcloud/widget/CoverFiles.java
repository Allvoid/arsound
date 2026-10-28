package app.revanced.extension.soundcloud.widget;

import android.content.Context;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;

/** The covers of the "Your likes" widget that Arsound keeps itself: where they lie, which files are pictures, what is kept. */
final class CoverFiles {
    /** Covers of the widget downloaded here, when the HTTP cache has no picture for them. */
    private static final String CACHE_FOLDER = "arsound-widget";
    /** The widget shows ten tracks in three sizes; older covers beyond this are deleted. */
    private static final int MAX_FILES = 60;
    /** A cover the server refused (a missing avatar answers 404) is not asked for again for this long. */
    private static final long MISS_KEPT_MS = 6 * 60 * 60 * 1000L;

    private CoverFiles() {
    }

    /** The place of the cover of this address. */
    static File of(Context context, String url) {
        return new File(new File(context.getCacheDir(), CACHE_FOLDER), Integer.toHexString(url.hashCode()) + ".img");
    }

    /** True when the file holds a picture that can be decoded. */
    static boolean isPicture(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    /** The mark of a cover the server refused, next to the place of the cover. */
    static File missMark(File target) {
        return new File(target.getPath() + ".miss");
    }

    /** True while the server's refusal of this cover is recent enough not to ask again. */
    static boolean recentlyRefused(File miss) {
        return miss.isFile() && System.currentTimeMillis() - miss.lastModified() < MISS_KEPT_MS;
    }

    static void markMiss(File miss) {
        try {
            if (!miss.createNewFile()) {
                //noinspection ResultOfMethodCallIgnored
                miss.setLastModified(System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            // Asked for again at the next update.
        }
    }

    /** Deletes the oldest covers beyond the number that is kept. */
    static void trim(File folder) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** The path, the size and the first bytes of the file, to tell an empty entry from a page or an unknown format. */
    static String describe(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return path + " (no file)";
            byte[] head = new byte[16];
            int read;
            try (FileInputStream input = new FileInputStream(file)) {
                read = Math.max(0, input.read(head));
            }
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < read; i++) hex.append(String.format("%02x", head[i]));
            return path + " (" + file.length() + " bytes, starts with " + hex + ")";
        } catch (Throwable ex) {
            return path + " (unreadable: " + ex + ")";
        }
    }
}
