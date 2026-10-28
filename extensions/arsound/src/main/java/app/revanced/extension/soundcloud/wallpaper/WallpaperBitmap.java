package app.revanced.extension.soundcloud.wallpaper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The one bitmap every {@link WallpaperDrawable} draws: the baked copy of the picture, read from its file off the main
 * thread and kept in memory for as long as the system allows.
 */
final class WallpaperBitmap {
    private static final long RETRY_LOAD_MS = 30_000;

    private final Supplier<File> file;
    private final Runnable onLoaded;
    private final Object lock = new Object();
    private SoftReference<Bitmap> shown;
    /** The file changed since the bitmap in memory was read; the old one is drawn until the new one is there. */
    private volatile boolean stale;
    private final AtomicBoolean loading = new AtomicBoolean();
    /** When the last read of the baked copy failed, so a damaged file is not read again on every frame. */
    private volatile long failedAt;

    /**
     * @param file     The baked copy, null where the app has no folder yet.
     * @param onLoaded Called on the main thread once a bitmap is there, to draw the screens again.
     */
    WallpaperBitmap(Supplier<File> file, Runnable onLoaded) {
        this.file = file;
        this.onLoaded = onLoaded;
    }

    /**
     * The bitmap to draw, or null while it is read: the read happens off the main thread and the drawables are drawn
     * again when it is there, so opening a screen never waits for the picture.
     */
    Bitmap get() {
        Bitmap bitmap;
        synchronized (lock) {
            bitmap = shown == null ? null : shown.get();
        }
        boolean usable = bitmap != null && !bitmap.isRecycled();
        if (usable && !stale) return bitmap;
        if (System.currentTimeMillis() - failedAt >= RETRY_LOAD_MS && loading.compareAndSet(false, true)) {
            Utils.runOnBackgroundThread(this::load);
        }
        return usable ? bitmap : null;
    }

    /** The file changed on disk: the bitmap is read again, and a failed read may be tried at once. */
    void markChanged() {
        stale = true;
        failedAt = 0;
    }

    private void load() {
        try {
            // A change while the file is read marks the bitmap stale again, and it is read once more.
            stale = false;
            File baked = file.get();
            if (baked == null || !baked.isFile()) {
                synchronized (lock) {
                    shown = null;
                }
                return;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(baked.getPath(), options);
            if (bitmap == null) {
                failedAt = System.currentTimeMillis();
                return;
            }
            synchronized (lock) {
                shown = new SoftReference<>(bitmap);
            }
            Utils.runOnMainThread(onLoaded);
        } catch (Exception | OutOfMemoryError ex) {
            failedAt = System.currentTimeMillis();
            Logger.printException(() -> "Wallpaper: could not read the picture", ex);
        } finally {
            loading.set(false);
        }
    }
}
