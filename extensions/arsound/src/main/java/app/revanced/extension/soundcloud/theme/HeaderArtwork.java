package app.revanced.extension.soundcloud.theme;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The pictures behind the title of some pages: the orange hearts of "Your likes" and the golden cards of the
 * purchases page, and the hearts of the liked tracks cell in Downloads. They are images, not drawn from the colour
 * tokens, so a theme left them in SoundCloud's colours. (The hearts on the home screen's "Your likes" bar come from
 * the server as a picture link and stay as they are.)
 * <p>
 * The theme recolours them into its accent when it is built ({@link #tint}), and a setting hides them.
 */
public final class HeaderArtwork {
    /** The pictures, by resource name. */
    static final String[] NAMES = {"your_likes_banner", "collection_header", "liked_tracks_artwork"};

    private HeaderArtwork() {
    }

    /**
     * Called by {@code CollapsableAppBarBackground.Companion.a(resource)} for the opacity of the picture
     * (1 in SoundCloud). Zero leaves the bar with the page's plain background.
     */
    @SuppressWarnings("unused")
    public static float alpha(int resource) {
        return Settings.isHeaderArtworkHidden() ? 0f : 1f;
    }

    /**
     * The picture as the app ships it, as PNG bytes (lossless), or null if it could not be read. Unscaled: the copy
     * goes into the same density folder as the original and is scaled like it when shown.
     */
    static byte[] original(Resources resources, int resource) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
        if (bitmap == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    /**
     * The picture in the accent: the drawings are one colour over black, so the brightness of each pixel (its red
     * channel, the brightest in both pictures) becomes that much of the accent. Black stays black.
     *
     * @param original The picture as {@link #original} gives it, not one that was recoloured already.
     * @return PNG bytes, or null if the picture could not be read.
     */
    static byte[] tint(byte[] original, int accent) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(original, 0, original.length, options);
        if (bitmap == null) return null;
        Bitmap tinted = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        float r = ((accent >> 16) & 0xFF) / 255f;
        float g = ((accent >> 8) & 0xFF) / 255f;
        float b = (accent & 0xFF) / 255f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                r, 0, 0, 0, 0,
                g, 0, 0, 0, 0,
                b, 0, 0, 0, 0,
                0, 0, 0, 1, 0,
        })));
        new Canvas(tinted).drawBitmap(bitmap, 0, 0, paint);
        bitmap.recycle();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        tinted.compress(Bitmap.CompressFormat.PNG, 100, output);
        tinted.recycle();
        return output.toByteArray();
    }
}
