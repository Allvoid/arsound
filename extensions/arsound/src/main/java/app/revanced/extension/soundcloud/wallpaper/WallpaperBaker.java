package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Prepares the wallpaper's pictures: decodes a chosen picture within the memory a screen can spare, and cuts it to the
 * shape of this phone's screen and blurs it into the copy every screen draws ({@link WallpaperStore} keeps the files).
 */
final class WallpaperBaker {
    /** Behind a screen more is of no use, and a bigger picture costs memory each time it is baked again. */
    static final int MAX_SIDE = 2560;
    /** Decoding stops short of this many pixels, whatever the side: 12 million pixels are 48 MB as a bitmap. */
    static final int MAX_PIXELS = 12_000_000;
    /** The quality of every JPEG this class writes: the baked copy is shown big, behind the whole screen. */
    static final int JPEG_QUALITY = 95;

    private WallpaperBaker() {
    }

    /**
     * Cuts a picture to the shape of this phone's screen, blurs it and writes it to a temporary file in
     * {@code folder}, which is returned. The baked copy that is in use is not touched: the caller puts the file in
     * place when everything else it needs is ready.
     * <p>
     * The blur works on a shrunken copy (a box blur, three times, which is close to a Gaussian one) and the result is
     * stretched back: the picture is soft anyway, and a few hundred thousand pixels are blurred in a moment.
     *
     * @param blur     The strength of the blur chosen in the settings, in percent.
     * @param fileName The name of the baked copy, the temporary file is named after it.
     */
    static File bake(Context context, File original, File folder, int blur, String fileName) throws IOException {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        // A strongly blurred picture has no detail left to keep: half the size is enough, and four times lighter.
        int outWidth = Math.max(180, Math.min(shortSide, 1080) / (blur >= 30 ? 2 : 1));
        int outHeight = Math.round(outWidth * (float) longSide / shortSide);

        Bitmap source = decodeCovering(original, outWidth, outHeight);
        if (source == null) throw new IOException("The picture cannot be read");

        Bitmap result = null;
        try {
            result = render(source, outWidth, outHeight, blur);
            File temporary = File.createTempFile(fileName + ".", ".tmp", folder);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output);
                return temporary;
            } catch (IOException | RuntimeException | Error ex) {
                temporary.delete();
                throw ex;
            }
        } finally {
            source.recycle();
            if (result != null) result.recycle();
        }
    }

    /**
     * The middle of a picture cut to a frame of the given size and blurred: what {@link #bake} writes, and what the
     * preview of a wallpaper shows. The strength of the blur is relative to the width of the frame, so a small copy
     * looks like the big one. The source is not recycled.
     *
     * @param blur The strength chosen in the settings, in percent.
     */
    static Bitmap render(Bitmap source, int outWidth, int outHeight, int blur) {
        Rect middle = middle(source.getWidth(), source.getHeight(), outWidth, outHeight);
        float sigma = blur * 0.6f * outWidth / 1080f;
        int shrink = Math.max(1, Math.min(24, Math.round(sigma / 3f)));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        if (sigma < 0.5f) {
            new Canvas(result).drawBitmap(source, middle, new Rect(0, 0, outWidth, outHeight), paint);
            return result;
        }
        int smallWidth = Math.max(8, outWidth / shrink);
        int smallHeight = Math.max(8, outHeight / shrink);
        Bitmap small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(source, middle, new Rect(0, 0, smallWidth, smallHeight), paint);
        int[] pixels = new int[smallWidth * smallHeight];
        small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
        BoxBlur.apply(pixels, smallWidth, smallHeight, Math.max(1, Math.round(sigma / shrink - 0.5f)));
        small.setPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight);
        new Canvas(result).drawBitmap(small, null, new Rect(0, 0, outWidth, outHeight), paint);
        small.recycle();
        return result;
    }

    /**
     * The picture as small as it can be and still cover a frame of this size once scaled to fill it (the preview and
     * the bake both cut the middle out of it), or null when the file cannot be read. It is never decoded to more than
     * the picture's own size, and not bigger than needed: a photo of 40 million pixels would not fit in memory.
     */
    static Bitmap decodeCovering(File file, int frameWidth, int frameHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        float cover = Math.max(frameWidth / (float) bounds.outWidth, frameHeight / (float) bounds.outHeight);
        int sample = 1;
        while (cover * sample * 2 <= 1f) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), options);
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

    /** Decodes a picture so its long side is at most {@link #MAX_SIDE}, never more than {@link #MAX_PIXELS} in memory. */
    static Bitmap decodeToKeep(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE
                || (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) return null;
        int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longSide <= MAX_SIDE) return bitmap;
        float scale = MAX_SIDE / (float) longSide;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                Math.round(bitmap.getHeight() * scale), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }
}
