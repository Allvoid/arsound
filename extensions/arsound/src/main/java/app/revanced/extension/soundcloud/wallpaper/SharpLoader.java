package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import app.revanced.extension.shared.Logger;

/**
 * The second, sharp stage of a cell of the Wallhaven grid. Wallhaven's thumbnail is only 300 pixels on its long side,
 * which is soft on a phone, so for a cell that stays in view the full file is downloaded, shrunk while decoding to at
 * least the size of the cell, cut to the cell's shape and kept as a small JPEG in {@link GridDiskCache}. Used by
 * {@link ThumbnailLoader}; every method blocks, call them off the main thread.
 * <p>
 * Only small files on a network that is not metered are taken, see {@link SharpPolicy}.
 */
final class SharpLoader {
    private static final long CACHE_BYTES = 150L * 1024 * 1024;
    private static final int KEPT_QUALITY = 85;
    /** A little above the largest accepted file, so a size that the server rounds is not refused. */
    private static final int READ_LIMIT = (int) SharpPolicy.GRID_MAX_BYTES + 256 * 1024;

    private final Context context;
    private final GridDiskCache disk;

    SharpLoader(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        disk = new GridDiskCache(new File(this.context.getCacheDir(), "wallhaven-grid"), CACHE_BYTES);
    }

    /** The key of a picture at the size of a cell, in memory and on disk. */
    static String key(WallpaperItem item, int width, int height) {
        return item.id + "_" + width + "x" + height;
    }

    /** True when this picture may be downloaded in full for a cell: it is small and the network is not metered. */
    boolean mayLoad(WallpaperItem item) {
        return SharpPolicy.gridMayLoad(item.fileSize, NetworkCost.isMetered(context));
    }

    /** The sharp cell if an earlier visit kept it on disk, else null. */
    Bitmap fromDisk(WallpaperItem item, int width, int height) {
        File file = disk.get(key(item, width, height));
        if (file == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /**
     * Downloads the full file, makes the sharp cell of it and keeps it on disk.
     *
     * @return The cell's picture, or null when the file could not be read.
     */
    Bitmap fetch(WallpaperItem item, int width, int height) throws IOException {
        byte[] bytes = Wallhaven.readPicture(item.fullUrl, READ_LIMIT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = CoverCrop.sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) return null;

        Bitmap cell = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            int[] middle = CoverCrop.middle(decoded.getWidth(), decoded.getHeight(), width, height);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            new Canvas(cell).drawBitmap(decoded, new Rect(middle[0], middle[1], middle[0] + middle[2], middle[1] + middle[3]),
                    new Rect(0, 0, width, height), paint);
        } finally {
            decoded.recycle();
        }
        keep(item, width, height, cell);
        return cell;
    }

    private void keep(WallpaperItem item, int width, int height, Bitmap cell) {
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
            cell.compress(Bitmap.CompressFormat.JPEG, KEPT_QUALITY, jpeg);
            disk.put(key(item, width, height), jpeg.toByteArray());
        } catch (IOException | RuntimeException ex) {
            // The picture is still shown; only the next visit has to download it again.
            Logger.printInfo(() -> "Wallhaven: a sharp cell could not be kept: " + ex.getClass().getSimpleName());
        }
    }
}
