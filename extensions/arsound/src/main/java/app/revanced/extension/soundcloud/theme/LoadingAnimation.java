package app.revanced.extension.soundcloud.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Reflect;

/** SoundCloud's loading animation (a Lottie file, the drawing letter): recoloured to a theme's accent. */
final class LoadingAnimation {
    private LoadingAnimation() {
    }

    /**
     * The animation shows the letter as white image frames; each frame is recoloured, keeping its transparency.
     *
     * @param json The animation as the app ships it.
     * @return The animation file with the frames in this colour.
     */
    static byte[] tint(byte[] json, int color) throws Exception {
        JSONObject animation = new JSONObject(new String(json, "UTF-8"));
        JSONArray assets = animation.optJSONArray("assets");
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        String prefix = "data:image/png;base64,";
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String data = asset.optString("p");
            if (!data.startsWith(prefix)) continue;
            byte[] png = Base64.decode(data.substring(prefix.length()), Base64.DEFAULT);
            Bitmap frame = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (frame == null) continue;
            Bitmap tinted = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
            new Canvas(tinted).drawBitmap(frame, 0, 0, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            tinted.compress(Bitmap.CompressFormat.PNG, 100, output);
            asset.put("p", prefix + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            frame.recycle();
            tinted.recycle();
        }
        return animation.toString().getBytes("UTF-8");
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
