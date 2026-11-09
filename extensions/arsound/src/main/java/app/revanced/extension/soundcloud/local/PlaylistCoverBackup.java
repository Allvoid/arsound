package app.revanced.extension.soundcloud.local;

import android.graphics.Bitmap;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import app.revanced.extension.shared.Logger;

/**
 * The covers the user set on playlists, as small pictures for the settings backup. The file of a cover can be a megapixel
 * picture; the backup carries each one cut down to {@link #SIDE} pixels (a few tens of kilobytes) and stops when the
 * covers together reach {@link #BUDGET_CHARS}, so the file stays far under its limit. Covers of imported tracks are not
 * here: they belong to files of one phone.
 * <p>
 * The text of the pictures (base64) and the file's JSON are the settings screen's ({@code settings.PlaylistCoversMerge});
 * this class only turns covers into pictures and back. Both calls read and write the disk: off the main thread.
 */
public final class PlaylistCoverBackup {
    /** The side of the picture in the backup; the library shows a cover far smaller, the playlist header at most this big. */
    static final int SIDE = 400;
    private static final int QUALITY = 82;
    /** The most base64 text of all covers together. */
    public static final int BUDGET_CHARS = 2_400_000;
    /** The longest text of one picture the settings file takes (see {@code settings.PlaylistCoversMerge}). */
    static final int MAX_PICTURE_CHARS = 400_000;

    private PlaylistCoverBackup() {
    }

    /** Playlist urn to its cover as base64 of a small JPEG, until the budget is spent. */
    public static Map<String, String> snapshot() {
        Map<String, String> pictures = new LinkedHashMap<>();
        long spent = 0;
        // In the order of the urns, so two exports of the same covers are the same file even when the budget cuts some.
        for (Map.Entry<String, File> cover : new TreeMap<>(LocalCovers.playlistCoverFiles()).entrySet()) {
            try {
                byte[] small = small(read(cover.getValue()));
                if (small == null) continue;
                String text = Base64.encodeToString(small, Base64.NO_WRAP);
                // The importing side drops a picture longer than this, so it is not written at all.
                if (text.length() > MAX_PICTURE_CHARS || spent + text.length() > BUDGET_CHARS) continue;
                spent += text.length();
                pictures.put(cover.getKey(), text);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Could not read the cover of " + cover.getKey(), ex);
            }
        }
        return pictures;
    }

    /** Sets the covers of the backup on their playlists, replacing the ones those playlists have. @return How many were set. */
    public static int restore(Map<String, String> pictures) {
        int set = 0;
        for (Map.Entry<String, String> picture : pictures.entrySet()) {
            try {
                byte[] bytes = Base64.decode(picture.getValue(), Base64.DEFAULT);
                // A file that is not a picture must not become a cover.
                if (bytes.length == 0 || !isPicture(bytes)) continue;
                if (LocalCovers.setPlaylistCover(picture.getKey(), bytes)) set++;
            } catch (IllegalArgumentException ex) {
                Logger.printException(() -> "A cover in the backup is not readable: " + picture.getKey(), ex);
            }
        }
        return set;
    }

    private static boolean isPicture(byte[] bytes) {
        Bitmap bitmap = CoverImages.decodeThumbnail(bytes, SIDE);
        if (bitmap == null) return false;
        bitmap.recycle();
        return true;
    }

    /** The bytes of a file; {@code java.nio.file.Files} needs Android 8, and the app starts at 7. */
    private static byte[] read(File file) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(file.length(), 1024), 8 * 1024 * 1024));
            byte[] buffer = new byte[16 * 1024];
            for (int count; (count = in.read(buffer)) > 0; ) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    /** The picture cut down to {@link #SIDE}, or null when it cannot be read. */
    private static byte[] small(byte[] stored) {
        Bitmap bitmap = CoverImages.decodeThumbnail(stored, SIDE);
        if (bitmap == null) return null;
        try {
            Bitmap scaled = bitmap;
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > SIDE) {
                float scale = SIDE / (float) longest;
                scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
            if (scaled != bitmap) scaled.recycle();
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }
}
