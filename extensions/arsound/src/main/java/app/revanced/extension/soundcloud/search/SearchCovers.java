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
 * The covers and avatars of the Arsound search rows and pages. Kept in memory by size, read on threads of their own,
 * and a cover asked for by many rows at once (every track of an album has the album's) is downloaded once. A view
 * given another picture meanwhile is not touched: its tag names the picture it waits for.
 *
 * <p>A picture is an address at a size ({@link Picture}), not the address alone: a bitmap is decoded for the size it is
 * shown at, so the small one of a row must never be handed to the top of a page that shows it far bigger.
 */
final class SearchCovers {
    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    /** A cover or an avatar is tens of kilobytes; anything far bigger is not one. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final LruCache<Picture, Bitmap> CACHE = new LruCache<Picture, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(Picture picture, Bitmap bitmap) {
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
    private static final Map<Picture, List<ImageView>> WAITING = new HashMap<>();

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private SearchCovers() {
    }

    /**
     * Puts the picture into the view now if it is in memory, or when it has come. The view keeps what it shows until
     * then: the placeholder of the row.
     *
     * @param side The side the picture is shown at, in pixels: it is decoded no bigger than needed, and it is part of
     *             what the picture is kept under. One address asked for at two sides (a row and the top of a page)
     *             is two bitmaps, each decoded for its own place.
     */
    static void load(ImageView view, String address, int side, Shown shown) {
        if (address == null || address.isEmpty()) {
            // No picture: nothing that this view waited for is its own any more.
            view.setTag(null);
            return;
        }
        Picture picture = new Picture(address, side);
        view.setTag(picture);
        Bitmap cached = CACHE.get(picture);
        if (cached != null) {
            shown.show(view, cached);
            return;
        }
        List<ImageView> waiting = WAITING.get(picture);
        if (waiting != null) {
            waiting.add(view);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(view);
        WAITING.put(picture, waiting);
        POOL.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decode(download(address), side);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printInfo(() -> "Arsound search: no picture " + address + ": " + ex);
            }
            Bitmap loaded = bitmap;
            if (loaded != null) CACHE.put(picture, loaded);
            SearchThreads.MAIN.post(() -> {
                List<ImageView> views = WAITING.remove(picture);
                if (views == null || loaded == null) return;
                for (ImageView waitingView : views) {
                    if (picture.equals(waitingView.getTag())) shown.show(waitingView, loaded);
                }
            });
        });
    }

    /**
     * An address at the side it is shown at, in pixels. What the cache and the waiting views are keyed by, and what a
     * view's tag holds: a view given the same address at another side does not take this bitmap.
     */
    static final class Picture {
        final String address;
        final int side;

        Picture(String address, int side) {
            this.address = address;
            this.side = side;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Picture)) return false;
            Picture that = (Picture) other;
            return side == that.side && address.equals(that.address);
        }

        @Override
        public int hashCode() {
            return 31 * address.hashCode() + side;
        }

        @Override
        public String toString() {
            return address + " at " + side + " px";
        }
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
