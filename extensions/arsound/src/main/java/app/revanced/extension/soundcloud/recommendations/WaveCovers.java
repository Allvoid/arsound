package app.revanced.extension.soundcloud.recommendations;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The small covers of the tracks the wave played, for {@link WavePage}: SoundCloud's own 100-pixel picture of each, read
 * from its picture server and kept in memory while the app runs. Two at a time, so a long list does not hold the
 * network up. The image view remembers which cover it waits for, so a row given another track does not get the old one.
 */
final class WaveCovers {
    /** SoundCloud's name of its 100 by 100 pixel cover, put in place of {@code {size}} in the address template. */
    private static final String SIZE = "large";

    private static final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private static final ExecutorService loaders = Executors.newFixedThreadPool(2);

    private WaveCovers() {
    }

    /** The address of a track's small cover from SoundCloud's template, or null without a template. */
    static String address(String template) {
        if (template == null || template.isEmpty() || !template.startsWith("https://")) return null;
        return template.replace("{size}", SIZE);
    }

    /** Shows the cover at {@code address} in the view, at once when it was loaded before. */
    static void show(ImageView view, String address) {
        view.setTag(address);
        view.setImageDrawable(null);
        if (address == null) return;
        Bitmap known = cache.get(address);
        if (known != null) {
            view.setImageBitmap(known);
            return;
        }
        loaders.execute(() -> {
            Bitmap bitmap = load(address);
            if (bitmap == null) return;
            cache.put(address, bitmap);
            Utils.runOnMainThread(() -> {
                if (address.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap load(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "My Wave: cover not loaded: " + ex);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
