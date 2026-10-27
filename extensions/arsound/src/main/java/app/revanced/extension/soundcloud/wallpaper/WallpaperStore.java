package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The picture chosen as the app's background: where it is kept, how it is prepared and the one bitmap every
 * {@link WallpaperDrawable} draws.
 * <p>
 * Two files live in {@code files/arsound/wallpaper}, so "Reset data" keeps them: {@code current.jpg}, the picture as
 * chosen (long side at most 2560, not cropped), and {@code view.jpg}, a copy cut to the screen's shape and blurred
 * once, so nothing is blurred while drawing. A blur slider change bakes the copy again from the first file.
 */
public final class WallpaperStore {
    public static final int DEFAULT_DIM = 65;
    public static final int DEFAULT_BLUR = 8;

    private static final String FOLDER = "arsound/wallpaper";
    private static final String ORIGINAL = "current.jpg";
    private static final String BAKED = "view.jpg";
    private static final String META = "meta.json";
    /** Behind a screen more is of no use, and a bigger picture costs memory each time it is baked again. */
    private static final int MAX_SIDE = 2560;
    /** Decoding stops short of this many pixels, whatever the side: 12 million pixels are 48 MB as a bitmap. */
    private static final int MAX_PIXELS = 12_000_000;
    private static final long RETRY_LOAD_MS = 30_000;

    private static final Object LOCK = new Object();
    private static SoftReference<Bitmap> shown;
    /** The file changed since the bitmap in memory was read; the old one is drawn until the new one is there. */
    private static volatile boolean stale;
    private static final AtomicBoolean loading = new AtomicBoolean();
    /** When the last read of the baked copy failed, so a damaged file is not read again on every frame. */
    private static volatile long failedAt;
    private static volatile int dim = -1;
    /** Every drawable that shows the picture, told when it changes. */
    private static final Set<WallpaperDrawable> watchers = Collections.newSetFromMap(new WeakHashMap<>());
    /** Screens that put the picture there themselves (the home screen), told when it is chosen, switched or removed. */
    private static final Set<Listener> listeners = Collections.newSetFromMap(new WeakHashMap<>());

    /** A screen that has to add or take away the wallpaper when it is chosen, switched or removed. */
    public interface Listener {
        void onWallpaperChanged();
    }

    private WallpaperStore() {
    }

    private static File folder() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FOLDER);
    }

    private static File file(String name) {
        File folder = folder();
        return folder == null ? null : new File(folder, name);
    }

    /** True when a wallpaper is chosen, whether it is switched on or not. */
    public static boolean has() {
        File baked = file(BAKED);
        return baked != null && baked.isFile();
    }

    /** True when the wallpaper is chosen and switched on: screens draw it. */
    public static boolean isActive() {
        return Settings.getBoolean(Settings.WALLPAPER_ENABLED, false) && has();
    }

    /** In percent: how much of the theme's surface colour lies over the picture. */
    public static int dim() {
        int value = dim;
        if (value < 0) dim = value = (int) Math.max(0, Math.min(95, Settings.getLong(Settings.WALLPAPER_DIM, DEFAULT_DIM)));
        return value;
    }

    /** Sets the dim live and keeps it; every screen that shows the picture is drawn again. */
    public static void setDim(int percent) {
        dim = Math.max(0, Math.min(95, percent));
        Settings.putLong(Settings.WALLPAPER_DIM, dim);
        invalidateAll();
    }

    /** In percent. */
    public static int blur() {
        return (int) Math.max(0, Math.min(100, Settings.getLong(Settings.WALLPAPER_BLUR, DEFAULT_BLUR)));
    }

    /**
     * Tells when a screen that shows the picture has to be built again: it changes with the switch, the strength of
     * dim and blur and the picture itself.
     */
    public static String stamp() {
        File baked = file(BAKED);
        return isActive() + "/" + dim() + "/" + blur() + "/" + (baked == null ? 0 : baked.lastModified());
    }

    /** The picture's page on Wallhaven, or null when it did not come from there. */
    public static String sourceUrl() {
        File meta = file(META);
        if (meta == null || !meta.isFile()) return null;
        try (FileInputStream input = new FileInputStream(meta)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) > 0) bytes.write(buffer, 0, count);
            String id = new JSONObject(bytes.toString("UTF-8")).optString("id");
            return id.isEmpty() ? null : Wallhaven.pageUrl(id);
        } catch (Exception ex) {
            return null;
        }
    }

    /** The wallpaper to draw behind a whole screen, or null when there is none to show. */
    public static WallpaperDrawable background(Context context) {
        return isActive() ? new WallpaperDrawable(context, 0) : null;
    }

    /** The wallpaper to draw as a band at the top of a screen, fading into the surface, or null. */
    public static WallpaperDrawable hero(Context context, int heightPx) {
        return isActive() ? new WallpaperDrawable(context, heightPx) : null;
    }

    /** Held weakly: the screen is forgotten with its view. */
    public static void listen(Listener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public static void unlisten(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    static void watch(WallpaperDrawable drawable) {
        synchronized (watchers) {
            watchers.add(drawable);
        }
    }

    /** Draws every screen that shows the picture again; call on the main thread. */
    private static void invalidateAll() {
        List<WallpaperDrawable> copy;
        synchronized (watchers) {
            copy = new ArrayList<>(watchers);
        }
        for (WallpaperDrawable drawable : copy) drawable.invalidateSelf();
    }

    /**
     * The bitmap to draw, or null while it is read: the read happens off the main thread and the drawables are drawn
     * again when it is there, so opening a screen never waits for the picture.
     */
    static Bitmap bitmap() {
        Bitmap bitmap;
        synchronized (LOCK) {
            bitmap = shown == null ? null : shown.get();
        }
        boolean usable = bitmap != null && !bitmap.isRecycled();
        if (usable && !stale) return bitmap;
        if (System.currentTimeMillis() - failedAt >= RETRY_LOAD_MS && loading.compareAndSet(false, true)) {
            Utils.runOnBackgroundThread(WallpaperStore::load);
        }
        return usable ? bitmap : null;
    }

    private static void load() {
        try {
            // A change while the file is read marks the bitmap stale again, and it is read once more.
            stale = false;
            File baked = file(BAKED);
            if (baked == null || !baked.isFile()) {
                synchronized (LOCK) {
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
            synchronized (LOCK) {
                shown = new SoftReference<>(bitmap);
            }
            Utils.runOnMainThread(WallpaperStore::invalidateAll);
        } catch (Exception | OutOfMemoryError ex) {
            failedAt = System.currentTimeMillis();
            Logger.printException(() -> "Wallpaper: could not read the picture", ex);
        } finally {
            loading.set(false);
        }
    }

    /** Reads the picture again (it changed on disk) and tells every screen that shows it. */
    public static void refresh() {
        stale = true;
        failedAt = 0;
        Utils.runOnMainThread(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener listener : copy) listener.onWallpaperChanged();
            invalidateAll();
        });
    }

    // region Choosing

    /**
     * Keeps a downloaded picture as the wallpaper, bakes it and switches the wallpaper on. Blocks: call it off the
     * main thread.
     *
     * @param id The picture's id on Wallhaven, for the link to its page.
     */
    public static boolean install(Context context, File downloaded, String id) {
        try {
            Bitmap bitmap = decode(downloaded, MAX_SIDE);
            if (bitmap == null) return false;
            File folder = folder();
            if (folder == null) return false;
            folder.mkdirs();
            File temporary = new File(folder, ORIGINAL + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output);
            } finally {
                bitmap.recycle();
            }
            replace(temporary, new File(folder, ORIGINAL));

            JSONObject meta = new JSONObject().put("id", id == null ? "" : id).put("source", "wallhaven");
            File metaFile = new File(folder, META);
            try (FileOutputStream output = new FileOutputStream(metaFile)) {
                output.write(meta.toString().getBytes("UTF-8"));
            }
            bake(context);
            Settings.putBoolean(Settings.WALLPAPER_ENABLED, true);
            refresh();
            return true;
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Wallpaper: could not keep the picture", ex);
            return false;
        }
    }

    /** Removes the wallpaper and switches it off. */
    public static void remove() {
        File folder = folder();
        File[] files = folder == null ? null : folder.listFiles();
        if (files != null) for (File file : files) file.delete();
        Settings.putBoolean(Settings.WALLPAPER_ENABLED, false);
        refresh();
    }

    /** Bakes the picture again after the blur changed, and draws it. Blocks: call it off the main thread. */
    public static void rebake(Context context) {
        try {
            if (file(ORIGINAL) == null || !file(ORIGINAL).isFile()) return;
            bake(context);
            refresh();
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Wallpaper: could not blur the picture", ex);
        }
    }

    private static void replace(File from, File to) throws IOException {
        to.delete();
        if (!from.renameTo(to)) throw new IOException("Could not rename " + from.getName());
    }

    // endregion

    // region Baking

    /**
     * Cuts the kept picture to the shape of this phone's screen, blurs it by the setting and writes {@code view.jpg}.
     * The blur works on a shrunken copy (a box blur, three times, which is close to a Gaussian one) and the result is
     * stretched back: the picture is soft anyway, and a few hundred thousand pixels are blurred in a moment.
     */
    private static void bake(Context context) throws IOException {
        File original = file(ORIGINAL);
        File baked = file(BAKED);
        if (original == null || baked == null) throw new IOException("No folder");

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        int blur = blur();
        // A strongly blurred picture has no detail left to keep: half the size is enough, and four times lighter.
        int outWidth = Math.max(180, Math.min(shortSide, 1080) / (blur >= 30 ? 2 : 1));
        int outHeight = Math.round(outWidth * (float) longSide / shortSide);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(original.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("The picture cannot be read");
        // The decoded picture only needs to cover the output once the middle is cut out of it.
        float cover = Math.max(outWidth / (float) bounds.outWidth, outHeight / (float) bounds.outHeight);
        int sample = 1;
        while (cover * sample * 2 <= 1f) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap source = BitmapFactory.decodeFile(original.getPath(), options);
        if (source == null) throw new IOException("The picture cannot be decoded");

        Bitmap result = null;
        try {
            Rect middle = middle(source.getWidth(), source.getHeight(), outWidth, outHeight);
            float sigma = blur * 0.6f * outWidth / 1080f;
            int shrink = Math.max(1, Math.min(24, Math.round(sigma / 3f)));
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (sigma < 0.5f) {
                result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                new Canvas(result).drawBitmap(source, middle, new Rect(0, 0, outWidth, outHeight), paint);
            } else {
                int smallWidth = Math.max(8, outWidth / shrink);
                int smallHeight = Math.max(8, outHeight / shrink);
                Bitmap small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
                new Canvas(small).drawBitmap(source, middle, new Rect(0, 0, smallWidth, smallHeight), paint);
                int[] pixels = new int[smallWidth * smallHeight];
                small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
                boxBlur(pixels, smallWidth, smallHeight, Math.max(1, Math.round(sigma / shrink - 0.5f)));
                small.setPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
                result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                new Canvas(result).drawBitmap(small, null, new Rect(0, 0, outWidth, outHeight), paint);
                small.recycle();
            }
            File temporary = new File(baked.getParentFile(), BAKED + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                result.compress(Bitmap.CompressFormat.JPEG, 90, output);
            }
            replace(temporary, baked);
        } finally {
            source.recycle();
            if (result != null) result.recycle();
        }
    }

    /** The part of a picture that fills a frame of the given shape when it is scaled up to cover it, centred. */
    private static Rect middle(int width, int height, int frameWidth, int frameHeight) {
        float frame = frameWidth / (float) frameHeight;
        if (width / (float) height > frame) {
            int cut = Math.round(height * frame);
            return new Rect((width - cut) / 2, 0, (width - cut) / 2 + cut, height);
        }
        int cut = Math.round(width / frame);
        return new Rect(0, (height - cut) / 2, width, (height - cut) / 2 + cut);
    }

    /** Decodes a picture so its long side is at most {@code maxSide}, never more than {@link #MAX_PIXELS} in memory. */
    private static Bitmap decode(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide
                || (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return null;
        int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longSide <= maxSide) return bitmap;
        float scale = maxSide / (float) longSide;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                Math.round(bitmap.getHeight() * scale), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    /** Blurs the picture's colours three times with a box of {@code radius}; the edges repeat their last pixel. */
    private static void boxBlur(int[] pixels, int width, int height, int radius) {
        int[] line = new int[Math.max(width, height)];
        int[] blurred = new int[line.length];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < height; y++) {
                System.arraycopy(pixels, y * width, line, 0, width);
                blurLine(line, blurred, width, radius);
                System.arraycopy(blurred, 0, pixels, y * width, width);
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) line[y] = pixels[y * width + x];
                blurLine(line, blurred, height, radius);
                for (int y = 0; y < height; y++) pixels[y * width + x] = blurred[y];
            }
        }
    }

    /** One row or column with a sliding sum per colour, so the cost does not depend on the radius. */
    private static void blurLine(int[] source, int[] target, int length, int radius) {
        int red = 0, green = 0, blue = 0;
        int window = radius * 2 + 1;
        for (int i = -radius; i <= radius; i++) {
            int pixel = source[Math.max(0, Math.min(length - 1, i))];
            red += (pixel >> 16) & 0xFF;
            green += (pixel >> 8) & 0xFF;
            blue += pixel & 0xFF;
        }
        for (int i = 0; i < length; i++) {
            // Rounded, not cut off: six passes of cutting would darken the picture.
            int half = window / 2;
            target[i] = 0xFF000000 | ((red + half) / window << 16) | ((green + half) / window << 8) | ((blue + half) / window);
            int leaving = source[Math.max(0, i - radius)];
            int entering = source[Math.min(length - 1, i + radius + 1)];
            red += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
            green += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
            blue += (entering & 0xFF) - (leaving & 0xFF);
        }
    }

    // endregion
}
