package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * One download of a Wallhaven picture into a file of its own in the cache. The preview starts it, so the full picture
 * is on its way while the user looks at it, and "Set as background" takes the same file instead of downloading
 * again. The file is not shared with any other download, so a download that was given up cannot take the file of the
 * next one away.
 * <p>
 * Start, cancel and listen on the main thread; the listener is told on the main thread too.
 */
public final class WallpaperDownload {
    /** Told how the download goes. */
    public interface Listener {
        /** @param total The size, or -1 when the server does not say. */
        void onProgress(long done, long total);

        /** @param saved False when the download failed or was given up. */
        void onFinished(boolean saved);
    }

    private static final long PROGRESS_STEP_MS = 100;

    private final File cacheFolder;
    private final String address;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Listener listener;
    private volatile File file;
    private boolean started;
    /** Read by {@link #install} on a thread of its own. */
    private volatile boolean finished, saved;
    private long done, total = -1;

    /** @param address The full picture's address on Wallhaven. */
    public WallpaperDownload(Context context, String address) {
        this.cacheFolder = context.getCacheDir();
        this.address = address;
    }

    /** Starts the download; nothing happens when it has started, finished or been given up already. */
    public void start() {
        if (started || cancelled.get()) return;
        started = true;
        File target;
        try {
            target = File.createTempFile("wallpaper-", ".tmp", cacheFolder);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Wallpaper: no file for the download: " + ex.getClass().getSimpleName());
            finish(false);
            return;
        }
        file = target;
        Utils.runOnBackgroundThread(() -> {
            boolean ok = false;
            long[] lastUpdate = {0};
            try {
                Wallhaven.download(address, target, (received, size) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] < PROGRESS_STEP_MS) return;
                    lastUpdate[0] = now;
                    Utils.runOnMainThread(() -> progress(received, size));
                }, cancelled);
                ok = true;
            } catch (IOException ex) {
                if (!cancelled.get()) Logger.printInfo(() -> "Wallhaven: the download failed: " + ex.getClass().getSimpleName());
            }
            boolean result = ok;
            Utils.runOnMainThread(() -> finish(result));
        });
    }

    /**
     * Takes the listener, which is told at once how far the download is or that it is over. A new listener replaces
     * the old one.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        if (finished) listener.onFinished(saved);
        else if (started && (done > 0 || total >= 0)) listener.onProgress(done, total);
    }

    /** True from the start until the download has finished or been given up. */
    public boolean isRunning() {
        return started && !finished;
    }

    /** True when the download ended without the picture and was not given up: it will not run again, a new one is needed. */
    public boolean isFailed() {
        return finished && !saved && !cancelled.get();
    }

    /** True when the whole picture is in the file. */
    public boolean isSaved() {
        return finished && saved;
    }

    /** The file with the whole picture, or null while there is none. */
    public File file() {
        return isSaved() ? file : null;
    }

    /** Gives up: the download stops and the file is removed. The listener is not told any more. */
    public void cancel() {
        cancelled.set(true);
        listener = null;
        finished = true;
        saved = false;
        discard();
    }

    /**
     * Keeps the picture as the wallpaper and removes the file. Blocks: call it off the main thread.
     *
     * @param id The picture's id on Wallhaven, for the link to its page.
     */
    public boolean install(Context context, String id) {
        File source = file();
        if (source == null || cancelled.get()) return false;
        try {
            return WallpaperStore.install(context, source, id);
        } finally {
            discard();
        }
    }

    private void discard() {
        File kept = file;
        if (kept != null) kept.delete();
    }

    private void progress(long received, long size) {
        if (finished) return;
        done = received;
        total = size;
        if (listener != null) listener.onProgress(done, total);
    }

    private void finish(boolean ok) {
        // Given up meanwhile: the file was removed and nobody is waiting.
        if (finished) return;
        finished = true;
        saved = ok;
        if (!ok) discard();
        if (listener != null) listener.onFinished(saved);
    }
}
