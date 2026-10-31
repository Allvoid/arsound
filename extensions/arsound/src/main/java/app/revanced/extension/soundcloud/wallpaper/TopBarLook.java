package app.revanced.extension.soundcloud.wallpaper;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Shader;

import java.lang.reflect.Method;

/**
 * The fills of SoundCloud's top bar over the wallpaper for the styles that are more than one colour
 * ({@link TopBarStyle#FADE}, {@link TopBarStyle#GLASS}). They are Compose brushes laid under the bar through its
 * modifier, built by reflection: the names of Compose's classes are kept in the app (see {@link HeaderShade}).
 * <p>
 * The glass shows the wallpaper under the bar strongly blurred ({@link #glassStrip}, shared with the preview in the
 * settings), a little lighter than the screen around it, with a sheen at the top and a light line along the bottom.
 * The tracks that scroll under it are hidden by it, as by thick frosted glass.
 */
public final class TopBarLook {
    /** How much of the top of the picture the glass strip covers: more than any top bar. */
    static final float STRIP = 0.3f;
    private static final int STRIP_WIDTH = 96;
    private static final int STRIP_BLUR = 5;
    /** How much more colourful the blurred strip is than the picture. */
    private static final float SATURATION = 1.5f;
    /** The light line along the bottom of the glass, as a part of its height. */
    private static final float EDGE = 0.012f;

    /** The baked picture the strip was made from: a new picture, or the same read again, is another bitmap. */
    private static Bitmap stripSource;
    private static Bitmap strip;
    private static Methods methods;

    // The brushes are made once per input and kept: the bar asks for them on every composition, and each was built by
    // reflection, the glass with a look at the picture's file too.
    private static long fadeSurface;
    private static Object fadeBrush;
    private static Bitmap glassFor;
    private static int glassWidth, glassHeight;
    private static Object glassBrush;
    private static int tintArgb;
    private static Object tintBrush;
    private static Object sheenBrush;

    private TopBarLook() {
    }

    /** The Compose functions the fills are built with, looked up once. */
    private static final class Methods {
        final Object companion;
        final Method verticalGradient, box, shaderBrush, background, rectangle;
        final Class<?> brush, pair;
        final java.lang.reflect.Constructor<?> newPair;

        Methods(ClassLoader loader) throws Exception {
            brush = Class.forName("androidx.compose.ui.graphics.Brush", false, loader);
            companion = brush.getField("Companion").get(null);
            verticalGradient = companion.getClass().getMethod("verticalGradient-8A-3gB4",
                    Class.forName("[Lkotlin.Pair;", false, loader), float.class, float.class, int.class);
            box = Class.forName("androidx.compose.ui.graphics.Color", false, loader).getMethod("box-impl", long.class);
            shaderBrush = Class.forName("androidx.compose.ui.graphics.BrushKt", false, loader)
                    .getMethod("ShaderBrush", Shader.class);
            Class<?> modifier = Class.forName("androidx.compose.ui.Modifier", false, loader);
            Class<?> shape = Class.forName("androidx.compose.ui.graphics.Shape", false, loader);
            background = Class.forName("androidx.compose.foundation.BackgroundKt", false, loader)
                    .getMethod("background", modifier, brush, shape, float.class);
            rectangle = Class.forName("androidx.compose.ui.graphics.RectangleShapeKt", false, loader)
                    .getMethod("getRectangleShape");
            pair = Class.forName("kotlin.Pair", false, loader);
            newPair = pair.getConstructor(Object.class, Object.class);
        }
    }

    private static Methods methods(ClassLoader loader) throws Exception {
        if (methods == null) methods = new Methods(loader);
        return methods;
    }

    /** The top of the wallpaper, small and strongly blurred; null while the picture is not read yet. */
    public static synchronized Bitmap glassStrip() {
        Bitmap baked = WallpaperStore.bitmap();
        if (baked == null) return null;
        // The strip depends only on the baked picture; asking its file for the time it changed cost a look at the
        // disk on every composition of the bar.
        if (strip != null && baked == stripSource) return strip;
        int height = Math.max(1, Math.round(baked.getHeight() * STRIP));
        int smallHeight = Math.max(1, Math.round(height * STRIP_WIDTH / (float) baked.getWidth()));
        Bitmap top = Bitmap.createBitmap(baked, 0, 0, baked.getWidth(), height);
        Bitmap small = Bitmap.createScaledBitmap(top, STRIP_WIDTH, smallHeight, true);
        if (top != baked && top != small) top.recycle();
        int[] pixels = new int[STRIP_WIDTH * smallHeight];
        small.getPixels(pixels, 0, STRIP_WIDTH, 0, 0, STRIP_WIDTH, smallHeight);
        if (small != baked) small.recycle();
        BoxBlur.apply(pixels, STRIP_WIDTH, smallHeight, STRIP_BLUR);
        Bitmap blurred = Bitmap.createBitmap(pixels, STRIP_WIDTH, smallHeight, Bitmap.Config.ARGB_8888);
        // Glass brightens the colours it blurs a little; grey glass looks like a dirty window.
        Bitmap saturated = Bitmap.createBitmap(STRIP_WIDTH, smallHeight, Bitmap.Config.ARGB_8888);
        android.graphics.ColorMatrix colour = new android.graphics.ColorMatrix();
        colour.setSaturation(SATURATION);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(colour));
        new android.graphics.Canvas(saturated).drawBitmap(blurred, 0, 0, paint);
        blurred.recycle();
        strip = saturated;
        stripSource = baked;
        return strip;
    }

    /**
     * Where the strip lies on a screen of the given size: the picture covers the screen as the window draws it
     * ({@link WallpaperDrawable}), and the strip is the top of the picture.
     */
    public static Matrix stripMatrix(Bitmap strip, int screenWidth, int screenHeight) {
        Bitmap baked = WallpaperStore.bitmap();
        Matrix matrix = new Matrix();
        if (baked == null) return matrix;
        float cover = Math.max(screenWidth / (float) baked.getWidth(), screenHeight / (float) baked.getHeight());
        float toBaked = baked.getWidth() / (float) strip.getWidth();
        matrix.setScale(toBaked * cover, toBaked * cover);
        matrix.postTranslate((screenWidth - baked.getWidth() * cover) / 2f, (screenHeight - baked.getHeight() * cover) / 2f);
        return matrix;
    }

    /** The colour of the glass over the blurred strip: the screen's colour, a little lighter than the dim around it. */
    public static int glassTint(int surface) {
        int alpha = Math.round(255 * Math.max(0.12f, WallpaperStore.dim() / 100f * 0.7f));
        return (surface & 0x00FFFFFF) | (alpha << 24);
    }

    // region Compose

    /**
     * The modifier of the bar with the fill under it, or the modifier as it was when the fill cannot be made.
     *
     * @param surface The screen's colour, packed as Compose packs it.
     */
    static Object fade(Object modifier, long surface) throws Exception {
        Methods m = methods(modifier.getClass().getClassLoader());
        if (fadeBrush == null || fadeSurface != surface) {
            fadeBrush = gradient(m, new float[]{0f, 0.55f, 1f}, new long[]{
                    HeaderShade.withAlpha(surface, 0.9f), HeaderShade.withAlpha(surface, 0.55f), HeaderShade.withAlpha(surface, 0f)});
            fadeSurface = surface;
        }
        return under(m, modifier, fadeBrush);
    }

    /**
     * The glass: the blurred strip, the tint, the sheen and the light edge, from the bottom up.
     *
     * @param screenWidth  The size of the screen the bar is at the top of, in pixels.
     */
    static Object glass(Object modifier, long surface, int surfaceArgb, int screenWidth, int screenHeight) throws Exception {
        Methods m = methods(modifier.getClass().getClassLoader());
        Bitmap strip = glassStrip();
        Object result = modifier;
        if (strip != null) {
            if (glassBrush == null || glassFor != strip || glassWidth != screenWidth || glassHeight != screenHeight) {
                BitmapShader shader = new BitmapShader(strip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(stripMatrix(strip, screenWidth, screenHeight));
                glassBrush = m.shaderBrush.invoke(null, shader);
                glassFor = strip;
                glassWidth = screenWidth;
                glassHeight = screenHeight;
            }
            result = under(m, result, glassBrush);
        }
        int tintColour = glassTint(surfaceArgb);
        if (tintBrush == null || tintArgb != tintColour) {
            long tint = packed(tintColour);
            tintBrush = gradient(m, new float[]{0f, 1f}, new long[]{tint, tint});
            tintArgb = tintColour;
        }
        result = under(m, result, tintBrush);
        if (sheenBrush == null) {
            long white = packed(0xFFFFFFFF);
            sheenBrush = gradient(m, new float[]{0f, 0.45f, 1f - EDGE * 2, 1f - EDGE, 1f}, new long[]{
                    HeaderShade.withAlpha(white, 0.10f), HeaderShade.withAlpha(white, 0.03f), HeaderShade.withAlpha(white, 0.01f),
                    HeaderShade.withAlpha(white, 0.24f), HeaderShade.withAlpha(white, 0.24f)});
        }
        return under(m, result, sheenBrush);
    }

    /** An sRGB colour as Compose packs it: the ARGB value in the high 32 bits. */
    static long packed(int argb) {
        return (argb & 0xFFFFFFFFL) << 32;
    }

    private static Object gradient(Methods m, float[] stops, long[] colours) throws Exception {
        Object pairs = java.lang.reflect.Array.newInstance(m.pair, stops.length);
        for (int i = 0; i < stops.length; i++) {
            Object pair = m.newPair.newInstance(stops[i], m.box.invoke(null, colours[i]));
            java.lang.reflect.Array.set(pairs, i, pair);
        }
        // From the top of the bar to its bottom; tile mode 0 is "clamp".
        return m.verticalGradient.invoke(m.companion, pairs, 0f, Float.POSITIVE_INFINITY, 0);
    }

    private static Object under(Methods m, Object modifier, Object brush) throws Exception {
        return m.background.invoke(null, modifier, brush, m.rectangle.invoke(null), 1f);
    }

    // endregion
}
