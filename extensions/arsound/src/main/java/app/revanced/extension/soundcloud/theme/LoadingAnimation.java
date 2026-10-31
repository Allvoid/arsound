package app.revanced.extension.soundcloud.theme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * SoundCloud's loading animation (a Lottie file): the А9 mark drawing itself, as stroked shapes. A theme gives the
 * letter line its text colour; the accent line keeps the logo's azure.
 */
final class LoadingAnimation {
    /** The accent line of the mark on light and on dark (a9.py AZURE_DEEP and AZURE). */
    static final int AZURE_DEEP = 0xFF1A66D9;
    static final int AZURE = 0xFF4C9BFF;

    private LoadingAnimation() {
    }

    /**
     * The animation in each pair of colours. The shapes are groups named "letter" and "accent" (tools/branding,
     * a9_android.lottie_loading), each holding a stroke; the colour of the stroke is set, the rest stays.
     *
     * @return The animation files, in the order of the pairs.
     */
    static byte[][] recolor(byte[] json, int[] letters, int[] accents) throws Exception {
        String text = new String(json, "UTF-8");
        byte[][] result = new byte[letters.length][];
        for (int c = 0; c < letters.length; c++) {
            JSONObject animation = new JSONObject(text);
            JSONArray layers = animation.optJSONArray("layers");
            for (int i = 0; layers != null && i < layers.length(); i++) {
                recolor(layers.getJSONObject(i).optJSONArray("shapes"), letters[c], accents[c]);
            }
            result[c] = animation.toString().getBytes("UTF-8");
        }
        return result;
    }

    private static void recolor(JSONArray shapes, int letter, int accent) throws Exception {
        for (int i = 0; shapes != null && i < shapes.length(); i++) {
            JSONObject shape = shapes.getJSONObject(i);
            String role = shape.optString("nm");
            if (!"gr".equals(shape.optString("ty")) || !("letter".equals(role) || "accent".equals(role))) continue;
            JSONArray items = shape.optJSONArray("it");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                if (!"st".equals(item.optString("ty"))) continue;
                int color = "letter".equals(role) ? letter : accent;
                item.put("c", new JSONObject().put("a", 0).put("k", new JSONArray()
                        .put(((color >> 16) & 0xFF) / 255.0).put(((color >> 8) & 0xFF) / 255.0)
                        .put((color & 0xFF) / 255.0).put(1)));
            }
        }
    }

    /**
     * Lottie keeps the animations it has read (the loading spinner among them) in a cache of its own, by resource id,
     * so after the palette changed in a running process the spinner would keep the old accent until the process ends.
     * The cache is emptied; the next spinner is read again from the new table. The fields of the cache have one-letter
     * names in SoundCloud's build, so they are found by their type, not by name.
     */
    static void forgetCached() {
        try {
            Class<?> cacheClass = Class.forName("com.airbnb.lottie.model.LottieCompositionCache");
            Class<?> lruClass = Class.forName("androidx.collection.LruCache");
            Field cacheField = Reflect.field(cacheClass, field -> field.getType() == cacheClass && Reflect.isStatic(field));
            Object cache = cacheField == null ? null : cacheField.get(null);
            if (cache == null) {
                Logger.printInfo(() -> "Theme: Lottie's cache was not found, the spinner keeps its colour until a restart");
                return;
            }
            for (Field field : cacheClass.getDeclaredFields()) {
                if (field.getType() != lruClass || Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object animations = field.get(cache);
                if (animations != null) lruClass.getMethod("evictAll").invoke(animations);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not empty Lottie's cache", ex);
        }
    }
}
