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

    /** A cover the server refused (a missing avatar answers 404) is not asked for again for this long. */
    private static final long MISS_KEPT_MS = 6 * 60 * 60 * 1000L;

    /** SoundCloud's HTTP client with the Arsound network rules, made once. */
    private static volatile Object client;

    /**
     * An OkHttp client of the app's own library, built through its builder: the network patch adds to it the custom
     * DNS and the block by IP country, as to every client of SoundCloud.
     */
    private static Object client() throws Exception {
        Object known = client;
        if (known != null) return known;
        synchronized (WidgetImages.class) {
            if (client == null) {
                Object builder = Class.forName("okhttp3.OkHttpClient$Builder", true, WidgetImages.class.getClassLoader())
                        .getConstructor().newInstance();
                client = builder.getClass().getMethod("build").invoke(builder);
            }
            return client;
        }
    }

    /** The mark of a cover the server refused, next to the place of the cover. */
    private static File missMark(File target) {
        return new File(target.getPath() + ".miss");
    }

    /**
     * Downloads a cover into {@code target}, through a temporary file so a half-written one is never used. A cover
     * the server refused is marked, so the widget's every update does not ask for it again.
     */
    private static boolean download(String url, File target) {
        File part = new File(target.getPath() + ".part");
        File miss = missMark(target);
        if (miss.isFile() && System.currentTimeMillis() - miss.lastModified() < MISS_KEPT_MS) return false;
        java.io.Closeable response = null;
        try {
            File folder = target.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            ClassLoader loader = WidgetImages.class.getClassLoader();
            Class<?> requestClass = Class.forName("okhttp3.Request", true, loader);
            Object requestBuilder = Class.forName("okhttp3.Request$Builder", true, loader).getConstructor().newInstance();
            requestBuilder.getClass().getMethod("url", String.class).invoke(requestBuilder, url);
            Object request = requestBuilder.getClass().getMethod("build").invoke(requestBuilder);
            Object http = client();
            Object call = http.getClass().getMethod("newCall", requestClass).invoke(http, request);
            response = (java.io.Closeable) Class.forName("okhttp3.Call", true, loader).getMethod("execute").invoke(call);
            int code = (int) response.getClass().getMethod("code").invoke(response);
            if (code != 200) {
                Logger.printInfo(() -> "Widget: cover answered " + code + ": " + url);
                if (code >= 400 && code < 500 && code != 408 && code != 429) markMiss(miss);
                return false;
            }
            Object body = response.getClass().getMethod("body").invoke(response);
            try (java.io.InputStream input = (java.io.InputStream) body.getClass().getMethod("byteStream").invoke(body);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(part)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1; ) {
                    total += read;
                    if (total > MAX_BYTES) {
                        markMiss(miss);
                        return false;
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (!isPicture(part)) {
                Logger.printInfo(() -> "Widget: the download is not a picture: " + describe(part.getPath()));
                markMiss(miss);
                return false;
            }
            if (!part.renameTo(target)) return false;
            //noinspection ResultOfMethodCallIgnored
            miss.delete();
            trim(folder);
            return true;
        } catch (Throwable ex) {
            // A network error or the country block: the cover is asked for again at the next update.
            Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null ? ex.getCause() : ex;
            Logger.printInfo(() -> "Widget: could not download the cover " + url + ": " + cause);
            return false;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                    // Closed with its body.
                }
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static void markMiss(File miss) {
        try {
            if (!miss.createNewFile()) {
                //noinspection ResultOfMethodCallIgnored
                miss.setLastModified(System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            // Asked for again at the next update.
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
