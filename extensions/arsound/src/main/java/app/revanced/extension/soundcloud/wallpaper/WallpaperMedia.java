package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import app.revanced.extension.shared.Logger;

/**
 * What a file picked as the wallpaper is: a still picture (JPEG, PNG, WebP, HEIC, AVIF, whatever Android can open), an
 * animated picture (GIF, animated WebP or AVIF) or a video (mp4, webm, mkv, whatever Android can play). A video or an
 * animation is reduced to a still frame, which goes through the same path as any other picture: it is the poster that
 * the window carries and the preview shows, and the media itself plays on top of it ({@link MediaWallpaper}).
 */
final class WallpaperMedia {
    /** A video or an animation bigger than this is refused: it would sit on the phone for good. */
    static final long MAX_BYTES = 400L * 1024 * 1024;

    enum Kind {
        STILL, ANIMATED, VIDEO, UNKNOWN
    }

    /** What the file is, and the picture to use as the still frame (the file itself for a still picture). */
    static final class Prepared {
        final Kind kind;
        /** A picture file that {@link WallpaperStore} keeps and bakes; for a still picture, the file that was picked. */
        final File picture;
        /** True when {@link #picture} was made here (a frame or a converted picture) and is to be deleted after use. */
        final boolean madeHere;

        Prepared(Kind kind, File picture, boolean madeHere) {
            this.kind = kind;
            this.picture = picture;
            this.madeHere = madeHere;
        }
    }

    private WallpaperMedia() {
    }

    /** Looks at the file; {@link Kind#UNKNOWN} when it is neither a picture nor a video Android can open. */
    static Prepared prepare(Context context, File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            boolean readable = bounds.outWidth > 0 && bounds.outHeight > 0;
            if (readable) {
                return new Prepared(isAnimated(file) ? Kind.ANIMATED : Kind.STILL, file, false);
            }
            // BitmapFactory could not read it: HEIC or AVIF on a phone whose decoder is only in ImageDecoder.
            File converted = convertWithDecoder(context, file);
            if (converted != null) return new Prepared(Kind.STILL, converted, true);
            File frame = videoFrame(context, file);
            if (frame != null) return new Prepared(Kind.VIDEO, frame, true);
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Wallpaper: could not look at the picked file", ex);
        }
        return new Prepared(Kind.UNKNOWN, file, false);
    }

    /** Whether the picture moves. Only Android 9 and later can play one; before it the first frame is a still picture. */
    static boolean isAnimated(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file),
                    (decoder, info, source) -> decoder.setTargetSampleSize(16));
            return drawable instanceof AnimatedImageDrawable;
        } catch (Exception | OutOfMemoryError ex) {
            return false;
        }
    }

    private static File convertWithDecoder(Context context, File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                int longSide = Math.max(width, height);
                if (longSide > WallpaperBaker.MAX_SIDE) {
                    float scale = WallpaperBaker.MAX_SIDE / (float) longSide;
                    decoder.setTargetSize(Math.round(width * scale), Math.round(height * scale));
                }
            });
            return writeJpeg(context, bitmap);
        } catch (Exception | OutOfMemoryError ex) {
            return null;
        }
    }

    /** A frame of the video, from about a second in (the first frame is often black), as a picture file; null if it has no video. */
    private static File videoFrame(Context context, File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            if (!"yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) return null;
            long duration = 0;
            try {
                duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (RuntimeException ignored) {
                // No length: the first frame.
            }
            long at = duration > 2500 ? 1_000_000L : 0L;
            Bitmap frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime();
            return frame == null ? null : writeJpeg(context, frame);
        } catch (Exception | OutOfMemoryError ex) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to release.
            }
        }
    }

    private static File writeJpeg(Context context, Bitmap bitmap) throws IOException {
        File out = File.createTempFile("arsound-wallpaper-frame", ".jpg", context.getCacheDir());
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, WallpaperBaker.JPEG_QUALITY, stream);
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            // An out of memory error in the encoder is not an IOException: the frame must not stay in the cache either.
            out.delete();
            throw ex;
        } finally {
            bitmap.recycle();
        }
        return out;
    }
}
