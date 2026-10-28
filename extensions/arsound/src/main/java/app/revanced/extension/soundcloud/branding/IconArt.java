package app.revanced.extension.soundcloud.branding;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/** The pictures of the icon variants: the adaptive icon of a variant and a small bitmap of it for the picker. */
final class IconArt {
    /** The resource names of a variant: the icon, and its background and foreground layers. */
    private static final String ICON = "arsound_icon_";
    private static final String BACKGROUND = "arsound_icon_bg_";
    private static final String FOREGROUND = "arsound_icon_fg_";

    private IconArt() {
    }

    private static int resource(ResourceType type, String prefix, String id) {
        return Utils.getResourceIdentifier(type, prefix + id);
    }

    /** True when the variant has a picture in the app. */
    static boolean hasPicture(String id) {
        return resource(ResourceType.MIPMAP, ICON, id) != 0;
    }

    /** The adaptive icon of a variant, drawn in the launcher's own shape. */
    static Drawable drawable(Context context, String id) {
        int resource = resource(ResourceType.MIPMAP, ICON, id);
        return resource == 0 ? null : context.getDrawable(resource);
    }

    /**
     * A small picture of a variant for the picker, in the launcher's own shape.
     * <p>
     * The layers of the bitmap variants are large images, and {@link #drawable} keeps them in memory for as long as
     * it is shown: over a hundred megabytes for the whole list. Here a variant is drawn once at the size it is shown
     * and its layers are freed at once. Reads image files, so call it off the main thread, one variant at a time.
     *
     * @param size The side of the picture in pixels.
     * @return Null if the picture could not be made.
     */
    static Drawable thumbnail(Context context, String id, int size) {
        try {
            Resources resources = context.getResources();
            int backgroundId = resource(ResourceType.DRAWABLE, BACKGROUND, id);
            int foregroundId = resource(ResourceType.DRAWABLE, FOREGROUND, id);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (backgroundId == 0 || foregroundId == 0)) {
                // SoundCloud's own icons: some layers are images, so the picture is drawn once at its size.
                Drawable ready = drawable(context, id);
                if (!(ready instanceof AdaptiveIconDrawable)) return ready;
                return render(resources, ready, size);
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || !(isBitmap(resources, backgroundId) || isBitmap(resources, foregroundId))) {
                // Vector variants are small, the ready icon costs next to nothing.
                return drawable(context, id);
            }

            // The launcher draws the layers a half larger than the icon it shows.
            int layerSize = size * 3 / 2;
            Drawable background = layer(context, backgroundId, layerSize);
            Drawable foreground = layer(context, foregroundId, layerSize);
            Drawable picture = render(resources, new AdaptiveIconDrawable(background, foreground), size);
            release(background);
            release(foreground);
            return picture;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not load the picture of the icon " + id, ex);
            return null;
        }
    }

    /** The drawable drawn once into a bitmap of {@code size} pixels a side. */
    private static Drawable render(Resources resources, Drawable drawable, int size) {
        Bitmap picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(picture));
        return new BitmapDrawable(resources, picture);
    }

    /** A layer of an icon: image files are read at about the given size, other files (vectors) as they are. */
    private static Drawable layer(Context context, int resource, int size) {
        Resources resources = context.getResources();
        if (!isBitmap(resources, resource)) return context.getDrawable(resource);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resource, options);
        int sample = 1;
        while (options.outWidth / (sample * 2) >= size) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, resource, options);
        if (bitmap == null) throw new IllegalStateException("Could not read the icon layer " + resource);
        return new BitmapDrawable(resources, bitmap);
    }

    private static boolean isBitmap(Resources resources, int resource) {
        TypedValue value = new TypedValue();
        resources.getValue(resource, value, true);
        String file = value.string == null ? "" : value.string.toString();
        return file.endsWith(".webp") || file.endsWith(".png") || file.endsWith(".jpg");
    }

    /** Gives the memory of a layer back right away, the picture made from it does not need it. */
    private static void release(Drawable layer) {
        if (layer instanceof BitmapDrawable) ((BitmapDrawable) layer).getBitmap().recycle();
    }
}
