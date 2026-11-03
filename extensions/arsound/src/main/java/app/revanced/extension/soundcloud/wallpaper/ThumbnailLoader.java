package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Loads the pictures of the Wallhaven grid in two stages and keeps the newest ones in a cache of bounded size.
 * <ol>
 *   <li>The thumbnail (300 pixels on its long side), on three threads. It is decoded with 8 bits per channel: 16 bits
 *   banded the skies and flat fills of anime pictures.</li>
 *   <li>The sharp cell ({@link SharpLoader}), on two more threads, only for cells that {@link WallhavenGrid} says
 *   stayed in view. A sharp cell kept on disk by an earlier visit is shown at once instead of the thumbnail.</li>
 * </ol>
 * The threads end after a while without work and with {@link #close}, so a closed screen leaves none of them behind.
 * A cell that was scrolled past before its turn does not load at all: every task checks that the view still wants it.
 */
final class ThumbnailLoader {
    private static final long THREAD_IDLE_SECONDS = 20;

    private final LruCache<String, Bitmap> cache;
    private final ThreadPoolExecutor pool;
    private final ThreadPoolExecutor sharpPool;
    private final SharpLoader sharp;
    private volatile boolean closed;
    /**
     * Thumbnails that could not be read, with the time, so scrolling does not ask for them over and over. A failure
     * expires after {@link #RETRY_MS}, and all of them when the network comes back: one failed read on a weak network
     * kept the cell shimmering until the next search.
     */
    private final java.util.Map<String, Long> failed = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_MS = 30_000;
    /** The cells that gave up, asked again when the network comes back. */
    private final java.util.Map<ImageView, Retry> gaveUp = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private final Context context;

    /** What a cell that gave up asked for. */
    private static final class Retry {
        final WallpaperItem item;
        final int width, height;

        Retry(WallpaperItem item, int width, int height) {
            this.item = item;
            this.width = width;
            this.height = height;
        }
    }
    /** Sharp cells being made or already given up on, by key. */
    private final Set<String> sharpTried = Collections.synchronizedSet(new HashSet<>());

    ThumbnailLoader(Context context) {
        // Bitmaps of 8 bits per channel are twice as big as the 16-bit ones were, so the cache is twice as big.
        int bytes = (int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        sharp = new SharpLoader(context);
        pool = newPool(3, "arsound-wallhaven-thumbnail");
        sharpPool = newPool(2, "arsound-wallhaven-sharp");
        this.context = context.getApplicationContext();
        watchNetwork();
    }

    /** When a network comes, the failures are forgotten and the cells on the screen that gave up load again. */
    private void watchNetwork() {
        try {
            android.net.ConnectivityManager manager =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    if (closed) return;
                    failed.clear();
                    Utils.runOnMainThread(ThumbnailLoader.this::retryGaveUp);
                }
            };
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ex) {
            networkCallback = null;
            Logger.printInfo(() -> "Wallhaven: the network is not watched: " + ex);
        }
    }

    private void retryGaveUp() {
        java.util.List<java.util.Map.Entry<ImageView, Retry>> cells;
        synchronized (gaveUp) {
            cells = new java.util.ArrayList<>(gaveUp.entrySet());
            gaveUp.clear();
        }
        for (java.util.Map.Entry<ImageView, Retry> cell : cells) {
            ImageView view = cell.getKey();
            Retry retry = cell.getValue();
            if (view == null || !retry.item.thumbnail.equals(view.getTag())) continue;
            if (view.getParent() instanceof WallhavenCell) ((WallhavenCell) view.getParent()).showLoading();
            load(retry.item, view, retry.width, retry.height);
        }
    }

    private static ThreadPoolExecutor newPool(int threads, String name) {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, THREAD_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** The screen is gone: the pictures still waiting for their turn are dropped and the threads end. */
    void close() {
        closed = true;
        pool.shutdownNow();
        sharpPool.shutdownNow();
        failed.clear();
        sharpTried.clear();
        gaveUp.clear();
        if (networkCallback != null) {
            try {
                ((android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // Already gone.
            }
            networkCallback = null;
        }
    }

    /** The thumbnail if it is in memory, else null. */
    Bitmap cached(String url) {
        return cache.get(url);
    }

    /** The sharp cell if it is in memory, else null. */
    Bitmap cachedSharp(WallpaperItem item, int width, int height) {
        return width <= 0 || height <= 0 ? null : cache.get(SharpLoader.key(item, width, height));
    }

    void forgetFailures() {
        failed.clear();
        sharpTried.clear();
    }

    /**
     * Loads the picture of a cell into the view, if the view still wants it when its turn comes. The view's tag is the
     * thumbnail's address: a cell reused for another picture changes it.
     *
     * @param width  The cell's width in pixels, 0 when not known yet.
     * @param height The cell's height in pixels.
     */
    void load(WallpaperItem item, ImageView view, int width, int height) {
        if (closed) return;
        Long failedAt = failed.get(item.thumbnail);
        if (failedAt != null) {
            if (android.os.SystemClock.uptimeMillis() - failedAt < RETRY_MS) {
                giveUp(item, view, width, height);
                return;
            }
            failed.remove(item.thumbnail);
        }
        try {
            pool.execute(() -> loadNow(item, view, width, height));
        } catch (RejectedExecutionException ex) {
            // Closed between the check and here.
        }
    }

    private void loadNow(WallpaperItem item, ImageView view, int width, int height) {
        String url = item.thumbnail;
        // The screen was closed, or the cell was reused for another picture, while this one waited.
        if (closed || !url.equals(view.getTag())) return;
        if (width > 0 && height > 0) {
            Bitmap kept = keptCell(item, width, height);
            if (kept != null) {
                show(url, view, kept, true);
                return;
            }
        }
        Bitmap bitmap = cache.get(url);
        if (bitmap == null) {
            try {
                byte[] bytes = Wallhaven.readThumbnail(url);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printInfo(() -> "Wallhaven: a thumbnail did not load: " + ex.getClass().getSimpleName());
            }
            if (bitmap == null) {
                if (!closed) {
                    failed.put(url, android.os.SystemClock.uptimeMillis());
                    Utils.runOnMainThread(() -> giveUp(item, view, width, height));
                }
                return;
            }
            cache.put(url, bitmap);
        }
        show(url, view, bitmap, true);
    }

    /** The sharp cell of an earlier visit, from memory or from disk, or null. */
    private Bitmap keptCell(WallpaperItem item, int width, int height) {
        String key = SharpLoader.key(item, width, height);
        Bitmap bitmap = cache.get(key);
        if (bitmap != null) return bitmap;
        try {
            bitmap = sharp.fromDisk(item, width, height);
        } catch (RuntimeException | OutOfMemoryError ex) {
            return null;
        }
        if (bitmap != null) cache.put(key, bitmap);
        return bitmap;
    }

    /**
     * Makes the picture of a cell sharp: shows the kept cell, or downloads the full file and makes one. Does nothing
     * when the file is big, the network is metered, or this cell was tried already.
     */
    void sharpen(WallpaperItem item, ImageView view, int width, int height) {
        if (closed || width <= 0 || height <= 0) return;
        String key = SharpLoader.key(item, width, height);
        if (!sharpTried.add(key)) return;
        try {
            sharpPool.execute(() -> sharpenNow(item, view, width, height, key));
        } catch (RejectedExecutionException ex) {
            sharpTried.remove(key);
        }
    }

    private void sharpenNow(WallpaperItem item, ImageView view, int width, int height, String key) {
        String url = item.thumbnail;
        if (closed || !url.equals(view.getTag())) {
            // Not tried really: the cell was gone, and may come back.
            sharpTried.remove(key);
            return;
        }
        Bitmap bitmap = keptCell(item, width, height);
        if (bitmap == null && !sharp.mayLoad(item)) {
            // Not tried really: the policy said no (big file, metered network), and may say yes after Wi-Fi.
            sharpTried.remove(key);
            return;
        }
        if (bitmap == null) {
            try {
                bitmap = sharp.fetch(item, width, height);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printInfo(() -> "Wallhaven: a sharp cell did not load: " + ex.getClass().getSimpleName());
            }
            if (bitmap != null) cache.put(key, bitmap);
        }
        if (bitmap != null) show(url, view, bitmap, false);
    }

    /**
     * The cell shows a still mark instead of the shimmer, which ran on for good, and is asked again when it is bound
     * after {@link #RETRY_MS} or when the network comes back. Main thread.
     */
    private void giveUp(WallpaperItem item, ImageView view, int width, int height) {
        if (closed || !item.thumbnail.equals(view.getTag())) return;
        gaveUp.put(view, new Retry(item, width, height));
        if (view.getParent() instanceof WallhavenCell) ((WallhavenCell) view.getParent()).showFailed();
    }

    /** Puts the bitmap in the view on the main thread, if the view still shows the picture the tag names. */
    private void show(String url, ImageView view, Bitmap bitmap, boolean fromEmpty) {
        Utils.runOnMainThread(() -> {
            if (closed || !url.equals(view.getTag())) return;
            view.setImageBitmap(bitmap);
            view.setBackground(null);
            view.setAlpha(fromEmpty ? 0f : .8f);
            view.animate().alpha(1f).setDuration(160).start();
        });
    }
}
