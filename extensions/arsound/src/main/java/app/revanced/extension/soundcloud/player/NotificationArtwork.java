package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Colors.blend;
import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Reflect;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The cover of the media notification.
 * <p>
 * It comes from the app's image provider, "&lt;package&gt;.imageProvider"; SoundCloud names it after its own package,
 * Arsound runs under another one. Android colours the notification player after the cover. With the new player on,
 * the notification gets the cover tinted in the theme's colours instead, so it looks like the theme; the user can choose the
 * track's colours instead.
 */
@SuppressWarnings("unused")
public final class NotificationArtwork {
    /** The notification player takes the colours of the playing track's cover. Shown under the new player. */
    public static final String TRACK_COLORS_KEY = "arsound_player_track_colors";

    private static final String ART = "android.media.metadata.ALBUM_ART";
    private static final String ART_URI = "android.media.metadata.ALBUM_ART_URI";
    /** Well under SoundCloud's own limit for a bitmap in the metadata (768 KB), which crosses processes. */
    private static final int SIZE = 320;

    private NotificationArtwork() {
    }

    /**
     * Injection point.
     *
     * @param original The package name SoundCloud put into the address.
     * @return The package the app is actually installed under, whose provider serves the cover.
     */
    public static String imageProviderPackage(String original) {
        Context context = Utils.getContext();
        return context == null ? original : context.getPackageName();
    }

    public static boolean isTrackColorsEnabled() {
        return Settings.getBoolean(TRACK_COLORS_KEY, false);
    }

    /** The track's cover SoundCloud loaded for the notification, kept between {@link #remember} and {@link #style}. */
    private static final ThreadLocal<Bitmap> cover = new ThreadLocal<>();

    /**
     * Injection point, at the start of building the metadata of a track.
     *
     * @param trackAndBitmap SoundCloud's track with the cover it loaded (or its placeholder) for the notification.
     */
    public static void remember(Object trackAndBitmap) {
        cover.remove();
        try {
            java.lang.reflect.Field field = Reflect.field(trackAndBitmap.getClass(), Reflect.typeIs(Bitmap.class));
            if (field != null) cover.set((Bitmap) field.get(trackAndBitmap));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the notification cover", ex);
        }
    }

    /**
     * Injection point, before SoundCloud builds the metadata of a track.
     *
     * @param builder The {@code MediaMetadataCompat.Builder} with the cover already put in.
     */
    public static void style(Object builder) {
        Bitmap trackCover = cover.get();
        cover.remove();
        try {
            if (!ArsoundTheme.isPlayerSkinEnabled() || isTrackColorsEnabled()) return;
            Context context = Utils.getContext();
            Bitmap themed = context == null ? null : picture(context, trackCover);
            if (themed == null) return;

            Class<?> type = builder.getClass();
            java.lang.reflect.Method putString = type.getMethod("putString", String.class, String.class);
            java.lang.reflect.Method putBitmap = type.getMethod("putBitmap", String.class, Bitmap.class);
            // The address would win over the bitmap, so it is cleared.
            putString.invoke(builder, ART_URI, null);
            putBitmap.invoke(builder, ART, themed);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not put the theme picture into the notification", ex);
        }
    }

    /**
     * The notification picture in the theme's colours: the track's cover, dimmed into the theme's dark background
     * on the left where the title stands and tinted with the accent, with a soft accent glow low on the right, where
     * Android keeps the buttons. Android takes the player's colours from it, so they follow the theme. Without a
     * cover, the background alone. Null for SoundCloud's own look.
     */
    private static Bitmap picture(Context context, Bitmap trackCover) {
        if (ArsoundTheme.SOUNDCLOUD.equals(ArsoundTheme.current())) return null;
        int[] colors = ArsoundTheme.darkColors(context);
        if (colors == null) return null;
        int surface = colors[0], accent = colors[1];
        boolean withCover = trackCover != null && !trackCover.isRecycled();

        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (withCover) {
            // Centre crop of the cover over the whole picture.
            int w = trackCover.getWidth(), h = trackCover.getHeight(), side = Math.min(w, h);
            android.graphics.Rect source = new android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
            canvas.drawBitmap(trackCover, source, new android.graphics.Rect(0, 0, SIZE, SIZE), paint);
            // The theme over it: its background on the left, fading out to the right; an accent veil on everything.
            paint.setShader(new LinearGradient(0, 0, SIZE, 0, withAlpha(surface, 0x99),
                    withAlpha(surface, 0x26), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, SIZE, SIZE, paint);
            paint.setShader(null);
            paint.setColor(withAlpha(accent, 0x2E));
            canvas.drawRect(0, 0, SIZE, SIZE, paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, SIZE, SIZE, surface, blend(surface, accent, .28f), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, SIZE, SIZE, paint);
        }
        paint.setShader(new RadialGradient(SIZE * .78f, SIZE * .7f, SIZE * .62f,
                withAlpha(accent, withCover ? 0x4D : 0x66), withAlpha(accent, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, SIZE, SIZE, paint);
        return bitmap;
    }
}
