package app.revanced.extension.soundcloud.wallpaper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The shade behind a playlist's header over the wallpaper: the screen's colour, clear at the top and at the bottom and
 * a little darker around the middle, so the title reads and the header has no edge of its own.
 * <p>
 * SoundCloud's own header is a blurred cover under a 40 % tint and fades into the screen's colour at both ends. Over a
 * wallpaper that was a dark square with hard edges.
 */
final class HeaderShade {
    /** How opaque the screen's colour is from the top of the header to its bottom, evenly spaced. */
    static final float[] ALPHAS = {0f, 0.12f, 0.24f, 0.30f, 0.24f, 0.10f, 0f};

    private static volatile Object companion;
    private static volatile Method box, verticalGradient;

    private HeaderShade() {
    }

    /** A packed sRGB Compose colour with its alpha replaced. */
    static long withAlpha(long color, float alpha) {
        long argb = color >>> 32;
        long a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return (((argb & 0x00FFFFFFL) | (a << 24)) & 0xFFFFFFFFL) << 32;
    }

    /**
     * {@code Brush.verticalGradient(colors)} of the app's Compose: its names are kept, the classes are found by name.
     *
     * @param surface The screen's colour, packed.
     * @return The {@code Brush}.
     */
    static Object brush(ClassLoader loader, long surface) throws Exception {
        if (verticalGradient == null) {
            Class<?> brushClass = Class.forName("androidx.compose.ui.graphics.Brush", false, loader);
            Class<?> colorClass = Class.forName("androidx.compose.ui.graphics.Color", false, loader);
            Field companionField = brushClass.getField("Companion");
            Object brushCompanion = companionField.get(null);
            Method boxMethod = colorClass.getMethod("box-impl", long.class);
            Method gradient = companionField.getType().getMethod("verticalGradient-8A-3gB4$default",
                    companionField.getType(), List.class, float.class, float.class, int.class, int.class, Object.class);
            companion = brushCompanion;
            box = boxMethod;
            verticalGradient = gradient;
        }
        List<Object> colors = new ArrayList<>();
        for (float alpha : ALPHAS) colors.add(box.invoke(null, withAlpha(surface, alpha)));
        // The mask 14 leaves the start, the end and the tile mode at their defaults: the whole height, clamped.
        return verticalGradient.invoke(null, companion, colors, 0f, 0f, 0, 14, null);
    }
}
