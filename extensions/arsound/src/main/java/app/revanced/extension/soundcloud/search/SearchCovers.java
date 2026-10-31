package app.revanced.extension.soundcloud.search;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * The covers and avatars of the Arsound search rows. Kept in memory by size, read on threads of their own, and a cover
 * asked for by many rows at once (every track of an album has the album's) is downloaded once. A row whose view was
 * given another picture meanwhile is not touched: the view's tag names the picture it waits for.
 */
final class SearchCovers {
    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    /** A cover or an avatar is tens of kilobytes; anything far bigger is not one. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String address, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "arsound-search-covers");
        thread.setDaemon(true);
        return thread;
    });
    /** The views waiting for each picture being downloaded. Main thread only. */
    private static final Map<String, List<ImageView>> WAITING = new HashMap<>();

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private SearchCovers() {
    }

    /**
     * Puts the picture into the view now if it is in memory, or when it has come. The view keeps what it shows until
     * then: the placeholder of the row.
     *
     * @param side The side the picture is shown at, in pixels: it is decoded no bigger than needed.
     */
    static void load(ImageView view, String address, int side, Shown shown) {
        view.setTag(address);
        if (address == null || address.isEmpty()) return;
        Bitmap cached = CACHE.get(address);
        if (cached != null) {
            shown.show(view, cached);
            return;
        }
        List<ImageView> waiting = WAITING.get(address);
        if (waiting != null) {
            waiting.add(view);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(view);
        WAITING.put(address, waiting);
        POOL.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decode(download(address), side);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printInfo(() -> "Arsound search: no picture " + address + ": " + ex);
            }
            Bitmap loaded = bitmap;
            if (loaded != null) CACHE.put(address, loaded);
            SearchThreads.MAIN.post(() -> {
                List<ImageView> views = WAITING.remove(address);
                if (views == null || loaded == null) return;
                for (ImageView waitingView : views) {
                    if (address.equals(waitingView.getTag())) shown.show(waitingView, loaded);
                }
            });
        });
    }

    /** How a row shows its picture once it is there. */
    interface Shown {
        void show(ImageView view, Bitmap bitmap);
    }

    private static byte[] download(String address) throws IOException {
        URL url = new URL(address);
        RegionGuard.throwIfBlocked(url.getHost());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_BYTES) throw new IOException("Not a cover: over " + MAX_BYTES + " bytes");
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** Decodes at about the side shown: a big picture never takes its full size in memory. */
    private static Bitmap decode(byte[] bytes, int side) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
