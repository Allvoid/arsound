package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

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
    /**
     * Held while the files of the wallpaper are written or removed. Every release of the blur slider bakes on a thread
     * of its own, and two bakes (or a bake and a new picture) would otherwise write the same files at once.
     */
    private static final Object WRITE = new Object();
    /** The baked copy in memory, drawn by every screen that shows the picture. */
    private static final WallpaperBitmap BITMAP = new WallpaperBitmap(() -> file(BAKED), WallpaperStore::invalidateAll);
    private static volatile int dim = -1;
    /** Every drawable that shows the picture, told when it changes. */
    private static final Set<WallpaperDrawable> watchers = Collections.newSetFromMap(new WeakHashMap<>());

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

    /** The wallpaper to draw behind a whole screen, or null when there is none to show. */
    public static WallpaperDrawable background(Context context) {
        return isActive() ? new WallpaperDrawable(context) : null;
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

    /** The bitmap to draw, or null while it is read; see {@link WallpaperBitmap#get}. */
    static Bitmap bitmap() {
        return BITMAP.get();
    }

    /** Reads the picture again (it changed on disk) and tells every screen that shows it. */
    public static void refresh() {
        BITMAP.markChanged();
        Utils.runOnMainThread(WallpaperStore::invalidateAll);
    }

    // region Choosing

    /**
     * Keeps a downloaded picture as the wallpaper, bakes it and switches the wallpaper on. Blocks: call it off the
     * main thread.
     *
     * @param id The picture's id on Wallhaven, for the link to its page.
     */
    public static boolean install(Context context, File downloaded, String id) {
        return install(context, downloaded, id, "wallhaven");
    }

    /**
     * Keeps a picture the user chose on the phone as the wallpaper; see {@link #install(Context, File, String)}.
     *
     * @param picture A copy of the picture in the app's cache; it is not kept.
     */
    public static boolean installOwn(Context context, File picture) {
        return install(context, picture, "", "phone");
    }

    /** @param source Where the picture comes from: "wallhaven", or "phone" for one the user chose on the phone. */
    private static boolean install(Context context, File downloaded, String id, String source) {
        synchronized (WRITE) {
            File temporary = null, metaTemporary = null, bakedTemporary = null;
            try {
                File folder = folder();
                if (folder == null) return false;
                folder.mkdirs();
                temporary = File.createTempFile(ORIGINAL + ".", ".tmp", folder);
                if (!keepPicture(downloaded, temporary)) return false;

                JSONObject meta = new JSONObject().put("id", id == null ? "" : id).put("source", source);
                metaTemporary = File.createTempFile(META + ".", ".tmp", folder);
                try (FileOutputStream output = new FileOutputStream(metaTemporary)) {
                    output.write(meta.toString().getBytes("UTF-8"));
                }
                // The slow step that can fail (a full disk, too little memory) runs before anything of the old
                // wallpaper is replaced: a failure leaves the old picture, its link and its blurred copy as they were.
                bakedTemporary = WallpaperBaker.bake(context, temporary, folder, blur(), BAKED);
                AtomicFiles.replace(temporary, new File(folder, ORIGINAL));
                AtomicFiles.replace(metaTemporary, new File(folder, META));
                AtomicFiles.replace(bakedTemporary, new File(folder, BAKED));
                Settings.putBoolean(Settings.WALLPAPER_ENABLED, true);
                refresh();
                return true;
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Wallpaper: could not keep the picture", ex);
                return false;
            } finally {
                // A rename took each away; what is still there is the rest of a failed try.
                if (temporary != null) temporary.delete();
                if (metaTemporary != null) metaTemporary.delete();
                if (bakedTemporary != null) bakedTemporary.delete();
            }
        }
    }

    /**
     * Writes the picture to {@code target} as the one to keep. A JPEG that is small enough is copied byte for byte (a
     * second pass through the encoder would only lose some of it); any other picture is decoded to at most
     * {@link WallpaperBaker#MAX_SIDE} on its long side and written as a JPEG of high quality.
     *
     * @return False when the file is not a picture that can be read.
     */
    private static boolean keepPicture(File downloaded, File target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(downloaded.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        if (WallpaperKeep.canKeepAsIs("image/jpeg".equals(bounds.outMimeType), bounds.outWidth, bounds.outHeight)) {
            try (FileInputStream input = new FileInputStream(downloaded); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            }
            return true;
        }
        Bitmap bitmap = WallpaperBaker.decodeToKeep(downloaded);
        if (bitmap == null) return false;
        try (FileOutputStream output = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, WallpaperBaker.JPEG_QUALITY, output);
        } finally {
            bitmap.recycle();
        }
        return true;
    }

    /**
     * Switches the wallpaper off at once and deletes its files off the main thread, then runs {@code after} on it. The
     * files wait for a bake that is running, and the main thread waited with them when "Remove" was pressed right
     * after a change of the blur.
     *
     * @param after Run on the main thread once the files are gone; may be null.
     */
    public static void removeInBackground(Runnable after) {
        Settings.putBoolean(Settings.WALLPAPER_ENABLED, false);
        refresh();
        Utils.runOnBackgroundThread(() -> {
            remove();
            if (after != null) Utils.runOnMainThread(after);
        });
    }

    /** Removes the wallpaper and switches it off. Waits for a bake that is running: call it off the main thread. */
    public static void remove() {
        synchronized (WRITE) {
            File folder = folder();
            File[] files = folder == null ? null : folder.listFiles();
            if (files != null) for (File file : files) file.delete();
            Settings.putBoolean(Settings.WALLPAPER_ENABLED, false);
        }
        refresh();
    }

    /** A bake was asked for since the running one started. */
    private static final java.util.concurrent.atomic.AtomicBoolean bakeWanted = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean baking = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Bakes the picture again after the blur changed, and draws it. Blocks: call it off the main thread. Releases of
     * the slider in a row bake once more after the running bake, with the last blur, instead of once per release:
     * the bakes queued up behind the lock. A call that finds a bake running returns at once; that bake does its work.
     */
    public static void rebake(Context context) {
        bakeWanted.set(true);
        if (!baking.compareAndSet(false, true)) return;
        try {
            while (bakeWanted.getAndSet(false)) {
                if (bakeKept(context)) refresh();
            }
        } finally {
            baking.set(false);
        }
        // Asked for between the end of the loop and the reset of the flag: nobody else bakes it.
        if (bakeWanted.get()) rebake(context);
    }

    /** @return Whether the kept picture was baked; false when there is none (removed) or it failed. */
    private static boolean bakeKept(Context context) {
        synchronized (WRITE) {
            try {
                if (file(ORIGINAL) == null || !file(ORIGINAL).isFile()) return false;
                bake(context);
                return true;
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Wallpaper: could not blur the picture", ex);
                return false;
            }
        }
    }

    // endregion

    // region Baking

    /** Bakes the kept picture again into {@code view.jpg}. */
    private static void bake(Context context) throws IOException {
        File original = file(ORIGINAL);
        File baked = file(BAKED);
        if (original == null || baked == null) throw new IOException("No folder");
        File prepared = WallpaperBaker.bake(context, original, baked.getParentFile(), blur(), BAKED);
        try {
            AtomicFiles.replace(prepared, baked);
        } finally {
            prepared.delete();
        }
    }

    /**
     * The picture as small as it can be and still cover a frame of this size once scaled to fill it, or null when the
     * file cannot be read; see {@link WallpaperBaker#decodeCovering}.
     */
    public static Bitmap decodeCovering(File file, int frameWidth, int frameHeight) {
        return WallpaperBaker.decodeCovering(file, frameWidth, frameHeight);
    }

    /**
     * The picture cut to a frame of the given size and blurred the way the baked copy is, for a preview that shows what
     * a choice of blur would give. The decoded picture is not recycled. Blocks: call it off the main thread.
     */
    public static Bitmap preview(Bitmap decoded, int width, int height, int blur) {
        return WallpaperBaker.render(decoded, width, height, blur);
    }

    // endregion
}
