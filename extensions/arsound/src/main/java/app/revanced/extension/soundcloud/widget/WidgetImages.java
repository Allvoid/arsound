package app.revanced.extension.soundcloud.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * Covers and the avatar of the "Your likes" home screen widget.
 * <p>
 * The widget takes each picture from the file of the HTTP cache and decodes it with
 * {@link BitmapFactory#decodeFile(String)}, then uses the result without a check. The server answers a missing
 * avatar with a small HTML error page, the cache keeps that page, and decoding it gave null: the whole widget
 * showed only "Error: Attempt to invoke virtual method 'getClass()' on a null object reference" (SoundCloud's own
 * widget does the same). Now the files are checked and missing covers downloaded ({@link #find}), and a picture
 * that still cannot be decoded is a plain grey square ({@link #decode}). The covers on disk are {@link CoverFiles},
 * the download is {@link CoverDownloader}.
 */
@SuppressWarnings("unused")
public final class WidgetImages {
    /** Dark grey like SoundCloud's own empty artwork in the widget. */
    private static final int PLACEHOLDER_COLOR = 0xFF333333;

    private static Bitmap placeholder;

    private WidgetImages() {
    }

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
    public static File find(Object finder, Context context, String url) {
        File own = CoverFiles.of(context, url);
        // The widget asks for the same cover from several threads at once; one of them downloads it. The locks are a
        // fixed set picked by the path: an interned path stayed in the process for good, for every cover ever shown.
        synchronized (LOCKS[(own.getPath().hashCode() & 0x7fffffff) % LOCKS.length]) {
            return find(finder, context, url, own);
        }
    }

    private static final Object[] LOCKS = new Object[16];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static File find(Object finder, Context context, String url, File own) {
        try {
            if (CoverFiles.isPicture(own)) {
                // Still shown: kept by the trim, which deletes the oldest files first.
                //noinspection ResultOfMethodCallIgnored
                own.setLastModified(System.currentTimeMillis());
                return own;
            }

            File cached = soundCloudsFile(finder, context, url);
            // SoundCloud's grey cloud for a cover it could not get is not the cover: try the address once more.
            if (cached != null && !cached.getName().startsWith("fallbackImage") && CoverFiles.isPicture(cached)) return cached;

            if (CoverDownloader.download(url, own)) return own;
            Logger.printInfo(() -> "Widget: no cover for " + url
                    + (cached == null ? "" : ", SoundCloud's file: " + CoverFiles.describe(cached.getPath())));
            return cached != null && CoverFiles.isPicture(cached) ? cached : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not find the cover " + url, ex);
            return null;
        }
    }

    /** What SoundCloud's own finder gives for the address, or null. */
    private static File soundCloudsFile(Object finder, Context context, String url) {
        try {
            return (File) finder.getClass().getMethod("b", Context.class, String.class).invoke(finder, context, url);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: SoundCloud's cover lookup failed for " + url, ex);
            return null;
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
        Logger.printInfo(() -> "Widget: not a picture, shown as an empty cover: " + CoverFiles.describe(path));
        return placeholder();
    }

    private static synchronized Bitmap placeholder() {
        if (placeholder == null) {
            placeholder = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(PLACEHOLDER_COLOR);
        }
        return placeholder;
    }
}
