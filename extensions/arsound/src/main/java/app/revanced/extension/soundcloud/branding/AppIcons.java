package app.revanced.extension.soundcloud.branding;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * The app icon the launcher shows.
 * <p>
 * Every variant is its own launcher entry (an activity alias of SoundCloud's start screen), and only one of
 * them is enabled. SoundCloud's own icon switch disables the start screen itself, which also breaks the app
 * restart and links that open it, so the start screen keeps no launcher entry and is never disabled.
 */
public final class AppIcons {
    /** Must match the alias names the branding patch adds to the manifest. */
    private static final String ALIAS_PREFIX = "com.soundcloud.android.launcher.ArsoundIcon_";

    /** Read each time: the app language can change while the app runs (the welcome switch). */
    private static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    public static final class Icon {
        public final String id;
        public final String name;
        public final String group;

        Icon(String[] row) {
            id = row[0];
            name = russian() ? row[1] : row[2];
            group = russian() ? row[3] : row[4];
        }
    }

    private AppIcons() {
    }

    public static List<Icon> all() {
        List<Icon> icons = new ArrayList<>();
        for (String[] row : rows()) icons.add(new Icon(row));
        return icons;
    }

    private static volatile List<String[]> rows;

    /**
     * The Arsound variants, then SoundCloud's own icons. A SoundCloud icon the patched version did not have has no
     * picture and no launcher entry, so it is left out.
     */
    private static List<String[]> rows() {
        List<String[]> known = rows;
        if (known != null) return known;
        List<String[]> all = new ArrayList<>(java.util.Arrays.asList(AppIconList.ICONS));
        for (String[] row : SoundCloudIconList.ICONS) {
            if (Utils.getResourceIdentifier(ResourceType.MIPMAP, "arsound_icon_" + row[0]) != 0) all.add(row);
        }
        rows = all;
        return all;
    }

    private static ComponentName component(Context context, String id) {
        return new ComponentName(context.getPackageName(), ALIAS_PREFIX + id);
    }

    /** The variant whose launcher entry is enabled; the default one if the user never changed it. */
    public static String current(Context context) {
        PackageManager manager = context.getPackageManager();
        for (String[] row : rows()) {
            try {
                if (manager.getComponentEnabledSetting(component(context, row[0]))
                        == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return row[0];
                }
            } catch (Exception ignored) {
                // An alias missing from the manifest.
            }
        }
        return AppIconList.ICONS[0][0];
    }

    /**
     * Shows another icon. The new entry is enabled before the old one is disabled, so the app always has one.
     * The launcher redraws within a few seconds; some launchers drop the shortcut from the home screen.
     */
    public static boolean apply(Context context, String id) {
        try {
            PackageManager manager = context.getPackageManager();
            manager.setComponentEnabledSetting(component(context, id),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (String[] row : rows()) {
                if (row[0].equals(id)) continue;
                ComponentName other = component(context, row[0]);
                int state = manager.getComponentEnabledSetting(other);
                // The default entry is enabled by the manifest, so it is switched off explicitly.
                boolean enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && row == AppIconList.ICONS[0]);
                if (enabled) {
                    manager.setComponentEnabledSetting(other,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
            }
            Logger.printInfo(() -> "App icon changed to " + id);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not change the app icon", ex);
            return false;
        }
    }

    /** The adaptive icon of a variant, drawn in the launcher's own shape. */
    public static Drawable drawable(Context context, String id) {
        int resource = Utils.getResourceIdentifier(ResourceType.MIPMAP, "arsound_icon_" + id);
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
    public static Drawable thumbnail(Context context, String id, int size) {
        try {
            Resources resources = context.getResources();
            int backgroundId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon_bg_" + id);
            int foregroundId = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_icon_fg_" + id);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (backgroundId == 0 || foregroundId == 0)) {
                // SoundCloud's own icons: some layers are images, so the picture is drawn once at its size.
                Drawable ready = drawable(context, id);
                if (!(ready instanceof AdaptiveIconDrawable)) return ready;
                Bitmap picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                ready.setBounds(0, 0, size, size);
                ready.draw(new Canvas(picture));
                return new BitmapDrawable(resources, picture);
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
            Bitmap picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            AdaptiveIconDrawable icon = new AdaptiveIconDrawable(background, foreground);
            icon.setBounds(0, 0, size, size);
            icon.draw(new Canvas(picture));
            release(background);
            release(foreground);
            return new BitmapDrawable(resources, picture);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not load the picture of the icon " + id, ex);
            return null;
        }
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
