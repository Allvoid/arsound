package app.revanced.extension.soundcloud.widget;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;

/** The colour a cover gives the widget's heart: its most vivid hue, as hue, saturation and value. */
final class CoverColor {
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

    private static final Object memoLock = new Object();
    private static WeakReference<Bitmap> memoCover = new WeakReference<>(null);
    private static int memoGeneration;
    private static float[] memoHsv;

    private CoverColor() {
    }

    /**
     * The hue, saturation and value the cover gives the heart, null if the cover has none. A play or pause update
     * builds the widget again with the same cover, so the last one is kept. It is kept as it was found, not as it
     * was fitted to the card: the phone can change its look between two updates with the same cover.
     */
    static float[] hsv(Bitmap cover) {
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
}
