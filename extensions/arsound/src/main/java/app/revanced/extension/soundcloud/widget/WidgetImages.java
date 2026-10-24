package app.revanced.extension.soundcloud.widget;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;

import app.revanced.extension.shared.Logger;

/**
 * Covers and the avatar of the "Your likes" home screen widget.
 * <p>
 * The widget takes each picture from the file of the HTTP cache and decodes it with
 * {@link BitmapFactory#decodeFile(String)}, then uses the result without a check. The server answers a missing
 * avatar with a small HTML error page, the cache keeps that page, and decoding it gave null: the whole widget
 * showed only "Error: Attempt to invoke virtual method 'getClass()' on a null object reference" (SoundCloud's own
 * widget does the same). Now the files are checked and missing covers downloaded ({@link #find}), and a picture
 * that still cannot be decoded is a plain grey square ({@link #decode}).
 */
@SuppressWarnings("unused")
public final class WidgetImages {
    /** Dark grey like SoundCloud's own empty artwork in the widget. */
    private static final int PLACEHOLDER_COLOR = 0xFF333333;

    private static Bitmap placeholder;

    private WidgetImages() {
    }

    /** Covers of the widget downloaded here, when the HTTP cache has no picture for them. */
    private static final String CACHE_FOLDER = "arsound-widget";
    private static final int TIMEOUT_MS = 15_000;
    /** Covers are tens of kilobytes; anything far bigger is not a cover. */
    private static final int MAX_BYTES = 4_000_000;
    /** The widget shows ten tracks in three sizes; older covers beyond this are deleted. */
    private static final int MAX_FILES = 60;

    /**
     * Called in place of {@code ImageFileFinder.b(context, url)}, the file of a cover for the widget.
     * <p>
     * SoundCloud's finder returns the body kept by the HTTP cache under that address, whatever it is: an error page
     * that the server sent for a missing avatar was given as the picture. And a cover that another image loader
     * downloaded is not in that cache, so the finder fell back to a grey cloud. Here the cached body is used only if
     * it is a picture; otherwise the cover is downloaded into a folder of its own.
     *
     * @return The file, or null for the widget's own placeholder.
     */
    public static File find(Object finder, android.content.Context context, String url) {
        File own = new File(new File(context.getCacheDir(), CACHE_FOLDER), Integer.toHexString(url.hashCode()) + ".img");
        // The widget asks for the same cover from several threads at once; one of them downloads it.
        synchronized (own.getPath().intern()) {
            return find(finder, context, url, own);
        }
    }

    private static File find(Object finder, android.content.Context context, String url, File own) {
        try {
            if (isPicture(own)) {
                // Still shown: kept by the trim, which deletes the oldest files first.
                //noinspection ResultOfMethodCallIgnored
                own.setLastModified(System.currentTimeMillis());
                return own;
            }

            File found = null;
            try {
                found = (File) finder.getClass().getMethod("b", android.content.Context.class, String.class)
                        .invoke(finder, context, url);
            } catch (Throwable ex) {
                Logger.printException(() -> "Widget: SoundCloud's cover lookup failed for " + url, ex);
            }
            File cached = found;
            // SoundCloud's grey cloud for a cover it could not get is not the cover: try the address once more.
            if (cached != null && !cached.getName().startsWith("fallbackImage") && isPicture(cached)) return cached;

            if (download(url, own)) return own;
            Logger.printInfo(() -> "Widget: no cover for " + url
                    + (cached == null ? "" : ", SoundCloud's file: " + describe(cached.getPath())));
            return cached != null && isPicture(cached) ? cached : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not find the cover " + url, ex);
            return null;
        }
    }

    private static boolean isPicture(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    /** Downloads a cover into {@code target}, through a temporary file so a half-written one is never used. */
    private static boolean download(String url, File target) {
        java.net.HttpURLConnection connection = null;
        File part = new File(target.getPath() + ".part");
        try {
            java.net.URL address = new java.net.URL(url);
            // The same rule as for the app's own requests: nothing goes out from an IP of a chosen country.
            app.revanced.extension.soundcloud.network.RegionGuard.throwIfBlocked(address.getHost());
            File folder = target.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            connection = (java.net.HttpURLConnection) address.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code != 200) {
                Logger.printInfo(() -> "Widget: cover answered " + code + ": " + url);
                return false;
            }
            try (java.io.InputStream input = connection.getInputStream();
                 java.io.FileOutputStream output = new java.io.FileOutputStream(part)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1; ) {
                    total += read;
                    if (total > MAX_BYTES) return false;
                    output.write(buffer, 0, read);
                }
            }
            if (!isPicture(part)) {
                Logger.printInfo(() -> "Widget: the download is not a picture: " + describe(part.getPath()));
                return false;
            }
            if (!part.renameTo(target)) return false;
            trim(folder);
            return true;
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Widget: could not download the cover " + url + ": " + ex);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static void trim(File folder) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Called in place of {@code BitmapFactory.decodeFile(path)} by the widget. Never null. */
    public static Bitmap decode(String path) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(path);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not decode " + path, ex);
        }
        if (bitmap != null) return bitmap;
        Logger.printInfo(() -> "Widget: not a picture, shown as an empty cover: " + describe(path));
        return placeholder();
    }

    private static synchronized Bitmap placeholder() {
        if (placeholder == null) {
            placeholder = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(PLACEHOLDER_COLOR);
        }
        return placeholder;
    }

    /** The path, the size and the first bytes of the file, to tell an empty entry from a page or an unknown format. */
    private static String describe(String path) {
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
