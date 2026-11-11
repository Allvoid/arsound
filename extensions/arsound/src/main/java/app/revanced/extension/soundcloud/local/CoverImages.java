package app.revanced.extension.soundcloud.local;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Pictures of covers: downloading, making them square, decoding them small, and the names of the stored files.
 * No state; {@link LocalCovers} decides what is stored and where it is used.
 */
final class CoverImages {
    private static final String EXTENSION = ".jpg";
    /** The longest side of a stored cover. */
    private static final int MAX_SIDE = 1280;
    /** A picture that cannot be decoded is kept as it is up to this size. */
    private static final int MAX_UNREADABLE_BYTES = 1024 * 1024;

    private CoverImages() {
    }

    /** The file of a stored cover. */
    static File coverFile(File directory, String hash) {
        return new File(directory, hash + EXTENSION);
    }

    /** The address of the file of a stored cover, as the app's lists take it. */
    static String fileAddress(File directory, String hash) {
        return Uri.fromFile(coverFile(directory, hash)).toString();
    }

    static boolean isCoverFile(File file) {
        return file.getName().endsWith(EXTENSION);
    }

    /** The hash a cover file is named by. */
    static String hashOf(File coverFile) {
        return coverFile.getName().replace(EXTENSION, "");
    }

    /** The most a downloaded picture may be: a cover is far less, and an answer without end must not fill the memory. */
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;

    /** Downloads a picture, at most {@link #MAX_DOWNLOAD_BYTES}. Blocks. */
    static byte[] download(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        RegionGuard.throwIfBlocked(url.getHost());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try (InputStream input = connection.getInputStream()) {
            if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) throw new java.io.IOException("The picture is too big");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_DOWNLOAD_BYTES) throw new java.io.IOException("The picture is too big");
            }
            return output.toByteArray();
        }
    }

    /** Decodes a picture at about the wanted width: a big picture never takes its full size in memory. */
    static Bitmap decodeThumbnail(byte[] bytes, int side) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    /**
     * Covers are shown in square cells. A video thumbnail is a 16:9 frame with the art in the middle
     * and black bars around it, so it is cut to the centre square. A big picture, like a photo from
     * the gallery, is made smaller while it is decoded: it never takes its full size in memory.
     *
     * @return The same array if the picture is square and small already, or cannot be read and is small.
     * @throws IOException If the picture cannot be read and is big.
     */
    static byte[] square(byte[] image) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        int width = bounds.outWidth, height = bounds.outHeight;
        if (width > 0 && width == height && width <= MAX_SIDE) return image;
        byte[] cut = width > 0 && height > 0 ? cutSquare(image, width, height) : null;
        if (cut != null) return cut;
        if (image.length > MAX_UNREADABLE_BYTES) throw new IOException("The picture cannot be read: " + image.length + " bytes");
        return image;
    }

    /** @return The centre square of the picture, at most {@link #MAX_SIDE} wide, or null if it cannot be decoded. */
    private static byte[] cutSquare(byte[] image, int width, int height) {
        Bitmap bitmap = null;
        try {
            // Decoded in steps of two that keep the short side (the square) at or above the wanted size; the rest
            // is scaled below.
            int sample = 1;
            while (Math.min(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            bitmap = BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (bitmap == null) return null;
            int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
            Bitmap centre = Bitmap.createBitmap(bitmap,
                    (bitmap.getWidth() - side) / 2, (bitmap.getHeight() - side) / 2, side, side);
            if (centre != bitmap) {
                bitmap.recycle();
                bitmap = centre;
            }
            if (side > MAX_SIDE) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, MAX_SIDE, MAX_SIDE, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            // A transparent picture would get black corners as a JPEG.
            boolean done = bitmap.hasAlpha()
                    ? bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    : bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
            return done ? output.toByteArray() : null;
        } catch (OutOfMemoryError error) {
            Logger.printException(() -> "Not enough memory to cut a picture of " + width + "x" + height, error);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}
