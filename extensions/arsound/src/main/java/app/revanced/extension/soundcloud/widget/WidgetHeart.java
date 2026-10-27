package app.revanced.extension.soundcloud.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The heart of SoundCloud's player widget on the home screen, in the colour of the playing track's cover.
 * <p>
 * Every update of the widget comes from one method that builds its {@code RemoteViews}. The launcher draws them, and
 * it never sees the theme the app puts on its own resources, so the colour has to be part of the {@code RemoteViews}:
 * a colour filter on the heart, sent with every update. The launcher keeps the last filter it was given, so an
 * update that wants SoundCloud's own heart sends a transparent one. The outlined heart (not liked) and the filled one
 * (liked) both take the colour, their shapes still tell them apart.
 * <p>
 * A grey or black-and-white cover has no colour to take, and neither has a track without a cover: the heart then
 * takes the accent of the chosen Arsound theme, and with SoundCloud's own look it stays SoundCloud's own heart.
 * <p>
 * The card behind the heart is light in the phone's day look and dark in its night look, so the colour is kept to
 * the luminance that stands out from the card of the look the phone has now.
 */
@SuppressWarnings("unused")
public final class WidgetHeart {
    /** The heart takes the cover's colour. On by default. */
    public static final String COVER_COLOR_KEY = "widget_heart_cover";

    /** The id of the heart in the widget's layout. */
    private static final String HEART_ID = "btn_like";

    /** The cover is looked at in this many pixels on a side: the colour of a cover does not need more. */
    private static final int SAMPLE = 32;
    /** Hues are counted in buckets of 15 degrees. */
    private static final int HUES = 24;
    /** Pixels greyer or darker than this are no colour of the cover. */
    private static final float MIN_SATURATION = 0.28f;
    private static final float MIN_VALUE = 0.25f;
    /** A hue needs this share of the cover (in pixels, counted by how vivid they are), else the cover is grey. */
    private static final double MIN_SHARE = 0.04;
    /** The heart is made at least this vivid, so it reads as a colour and not as a grey on the card. */
    private static final float MIN_HEART_SATURATION = 0.60f;
    private static final float MAX_HEART_SATURATION = 0.95f;
    /**
     * The luminance (as WCAG defines it) the heart is kept within. The card behind it is white or black at 65% over
     * the wallpaper; the worst cases are a light grey card (0xF0F0F0) and a dark one (0x1C1C1C). A luminance of 0.14
     * or more keeps the contrast at 3 or better on the dark card, 0.25 or less on the light one. The day band is that,
     * so it stands out from either card: the launcher can change the look without asking for a new update, and the
     * colour of the day look still reads on the dark card. The night band lets a bright cover stay bright, as the dark
     * card has the contrast to spare; if the phone goes back to its day look before the next update, the heart is a
     * light colour on a light card until then.
     */
    private static final double MIN_LUMINANCE = 0.14;
    private static final double MAX_LUMINANCE_DAY = 0.25;
    private static final double MAX_LUMINANCE_NIGHT = 0.45;

    private WidgetHeart() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(COVER_COLOR_KEY, true);
    }

    /** SoundCloud's provider of the player widget: it has the widget built again, from the track it has. */
    private static final String PROVIDER = "com.soundcloud.android.playback.widget.service.PlayerAppWidgetProvider";

    /**
     * Has the widget on the home screen built again, so a change of the setting shows at once and not with the next
     * track. It is the update the system sends when the widget is added; with no widget on the screen nothing happens.
     */
    public static void refresh(Context context) {
        try {
            ComponentName provider = new ComponentName(context, PROVIDER);
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(provider);
            if (ids == null || ids.length == 0) return;
            context.sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not ask for a new widget", ex);
        }
    }

    /** The colour chosen at the start of the update running on this thread, kept for its end. */
    private static final ThreadLocal<Integer> chosen = new ThreadLocal<>();

    /**
     * Injection point, at the start of the method that builds the widget. The colour is chosen here, while the
     * builder still holds the cover.
     *
     * @param builder SoundCloud's {@code PlayerWidgetRemoteViewsBuilder}: the track, and the cover it loaded.
     * @param context The app, whose theme is the fallback.
     */
    public static void begin(Object builder, Context context) {
        chosen.remove();
        try {
            chosen.set(heartColor(builder, context));
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not choose the colour", ex);
        }
    }

    /**
     * Injection point, before the method that builds the widget returns it.
     *
     * @param views The finished widget.
     */
    public static void tint(RemoteViews views) {
        Integer color = chosen.get();
        chosen.remove();
        try {
            int heart = heartId();
            if (heart == 0 || views == null) return;
            // Sent every time, transparent when the heart should look as SoundCloud draws it.
            views.setInt(heart, "setColorFilter", color == null ? Color.TRANSPARENT : color);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not colour the heart", ex);
        }
    }

    private static volatile int heartId;

    private static int heartId() {
        int known = heartId;
        if (known == 0) {
            known = Utils.getResourceIdentifier(ResourceType.ID, HEART_ID);
            heartId = known;
        }
        return known;
    }

    /** @return The colour of the heart, or transparent for SoundCloud's own. */
    private static int heartColor(Object builder, Context context) throws Exception {
        if (!isEnabled()) return Color.TRANSPARENT;
        boolean night = isNight();
        float[] hsv = coverHsv(coverOf(builder));
        int color = hsv == null ? 0 : fit(hsv[0], hsv[1], hsv[2], night);
        String source = "cover";
        if (color == 0) {
            color = themeColor(context, night);
            source = "theme";
        }
        int result = color;
        String from = source;
        Logger.printDebug(() -> "Widget heart: " + (result == 0
                ? "SoundCloud's own"
                : String.format("#%06X from the ", result & 0xFFFFFF) + from));
        return result;
    }

    private static volatile Field coverField;

    /** The cover of the builder: the only bitmap it holds. */
    private static Bitmap coverOf(Object builder) throws Exception {
        Field field = coverField;
        if (field == null) {
            for (Field candidate : builder.getClass().getDeclaredFields()) {
                if (candidate.getType() != Bitmap.class) continue;
                candidate.setAccessible(true);
                field = candidate;
                break;
            }
            if (field == null) return null;
            coverField = field;
        }
        return (Bitmap) field.get(builder);
    }

    /** The accent of the chosen theme in the colours the heart is kept to, or 0 for SoundCloud's own look. */
    private static int themeColor(Context context, boolean night) {
        if (context == null || ArsoundTheme.SOUNDCLOUD.equals(ArsoundTheme.current())) return 0;
        float[] hsv = new float[3];
        Color.colorToHSV(ArsoundTheme.palette(context, "special"), hsv);
        return fit(hsv[0], hsv[1], hsv[2], night);
    }

    /**
     * True when the phone is in its night look, which the launcher draws the card in. Asked of the system, not of
     * the app: the app can keep its own screens dark whatever the phone does.
     */
    private static boolean isNight() {
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    // region The colour of a cover

    private static final Object memoLock = new Object();
    private static WeakReference<Bitmap> memoCover = new WeakReference<>(null);
    private static int memoGeneration;
    private static float[] memoHsv;

    /**
     * The hue, saturation and value the cover gives the heart, null if the cover has none. A play or pause update
     * builds the widget again with the same cover, so the last one is kept. It is kept as it was found, not as it
     * was fitted to the card: the phone can change its look between two updates with the same cover.
     */
    static float[] coverHsv(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        synchronized (memoLock) {
            if (memoCover.get() == cover && memoGeneration == cover.getGenerationId()) return memoHsv;
        }
        float[] hsv = extract(cover);
        synchronized (memoLock) {
            memoCover = new WeakReference<>(cover);
            memoGeneration = cover.getGenerationId();
            memoHsv = hsv;
        }
        return hsv;
    }

    /** The cover is handed to the widget in the same call: it is only read here, never recycled. */
    private static float[] extract(Bitmap cover) {
        Bitmap readable = cover;
        Bitmap small = null;
        try {
            // A bitmap in the graphics memory cannot be scaled or read pixel by pixel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cover.getConfig() == Bitmap.Config.HARDWARE) {
                readable = cover.copy(Bitmap.Config.ARGB_8888, false);
                if (readable == null) return null;
            }
            small = Bitmap.createScaledBitmap(readable, SAMPLE, SAMPLE, true);
            int[] pixels = new int[SAMPLE * SAMPLE];
            small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
            return dominantColor(pixels);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not read the cover", ex);
            return null;
        } finally {
            // Scaling to the size it already has returns the same bitmap.
            if (small != null && small != readable) small.recycle();
            if (readable != cover) readable.recycle();
        }
    }

    /**
     * The most vivid hue of the picture. Each pixel counts for how saturated and bright it is, so a small bright
     * detail wins over a large dull area, and the hue with the most (with its neighbours, so a hue on the edge of a
     * bucket is not split in two) gives the colour.
     *
     * @return The hue, saturation and value of the colour, or null if the picture has no hue of its own.
     */
    private static float[] dominantColor(int[] pixels) {
        double[] weight = new double[HUES];
        double[] saturation = new double[HUES];
        double[] value = new double[HUES];
        double[] cos = new double[HUES];
        double[] sin = new double[HUES];
        float[] hsv = new float[3];
        int counted = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 128) continue;
            counted++;
            Color.colorToHSV(pixel, hsv);
            if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue;
            double w = hsv[1] * hsv[1] * (0.4 + 0.6 * hsv[2]);
            int bucket = Math.min(HUES - 1, (int) (hsv[0] / (360f / HUES)));
            double radians = Math.toRadians(hsv[0]);
            weight[bucket] += w;
            saturation[bucket] += w * hsv[1];
            value[bucket] += w * hsv[2];
            cos[bucket] += w * Math.cos(radians);
            sin[bucket] += w * Math.sin(radians);
        }
        if (counted == 0) return null;

        int best = -1;
        double bestWeight = 0;
        for (int i = 0; i < HUES; i++) {
            double around = weight[i] + weight[(i + 1) % HUES] + weight[(i + HUES - 1) % HUES];
            if (around > bestWeight) {
                bestWeight = around;
                best = i;
            }
        }
        if (best < 0 || bestWeight < MIN_SHARE * counted) return null;

        double total = 0, saturationSum = 0, valueSum = 0, cosSum = 0, sinSum = 0;
        for (int step = -1; step <= 1; step++) {
            int i = (best + step + HUES) % HUES;
            total += weight[i];
            saturationSum += saturation[i];
            valueSum += value[i];
            cosSum += cos[i];
            sinSum += sin[i];
        }
        // The mean of hues is the direction of their sum: 350 and 10 degrees give 0, not 180.
        float hue = (float) Math.toDegrees(Math.atan2(sinSum, cosSum));
        if (hue < 0) hue += 360f;
        float vivid = (float) Math.max(MIN_HEART_SATURATION, Math.min(MAX_HEART_SATURATION, saturationSum / total));
        return new float[] {hue, vivid, (float) (valueSum / total)};
    }

    /**
     * The colour with this hue, brightened or darkened until its luminance is within the band of the look, and made
     * less saturated if even the brightest is too dark (a deep blue is lighter only by mixing with white).
     */
    private static int fit(float hue, float saturation, float value, boolean night) {
        double maxLuminance = night ? MAX_LUMINANCE_NIGHT : MAX_LUMINANCE_DAY;
        float[] hsv = {hue, saturation, Math.max(0.35f, Math.min(1f, value))};
        // Each step is a few percent of the luminance, far less than the band, so the loop settles; the limit only
        // keeps a colour that cannot settle from looping.
        for (int i = 0; i < 40; i++) {
            double luminance = luminance(Color.HSVToColor(hsv));
            if (luminance > maxLuminance && hsv[2] > 0.3f) {
                hsv[2] -= 0.03f;
            } else if (luminance < MIN_LUMINANCE && hsv[2] < 1f) {
                hsv[2] = Math.min(1f, hsv[2] + 0.03f);
            } else if (luminance < MIN_LUMINANCE && hsv[1] > 0.3f) {
                hsv[1] -= 0.03f;
            } else {
                break;
            }
        }
        return Color.HSVToColor(hsv);
    }

    /** The relative luminance of a colour, 0 for black to 1 for white. */
    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // endregion
}
