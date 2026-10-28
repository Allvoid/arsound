package app.revanced.extension.soundcloud.branding;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.util.List;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The app icon the launcher shows: the variants to choose from ({@link IconCatalog}), the one whose launcher entry is
 * enabled ({@link LauncherAlias}) and their pictures ({@link IconArt}).
 * <p>
 * Every variant is its own launcher entry (an activity alias of SoundCloud's start screen), and only one of
 * them is enabled.
 */
public final class AppIcons {
    public static final class Icon {
        public final String id;
        public final String name;
        public final String group;

        Icon(String[] row) {
            id = row[0];
            name = Lang.text(row[1], row[2]);
            group = Lang.text(row[3], row[4]);
        }
    }

    private AppIcons() {
    }

    public static List<Icon> all() {
        return IconCatalog.icons();
    }

    /** The variant whose launcher entry is enabled; the default one if the user never changed it. */
    public static String current(Context context) {
        return LauncherAlias.current(context);
    }

    /**
     * Shows another icon. The new entry is enabled before the old one is disabled, so the app always has one.
     * The launcher redraws within a few seconds; some launchers drop the shortcut from the home screen.
     */
    public static boolean apply(Context context, String id) {
        return LauncherAlias.apply(context, id);
    }

    /** The adaptive icon of a variant, drawn in the launcher's own shape. */
    public static Drawable drawable(Context context, String id) {
        return IconArt.drawable(context, id);
    }

    /**
     * A small picture of a variant for the picker, in the launcher's own shape. Reads image files, so call it off
     * the main thread, one variant at a time.
     *
     * @param size The side of the picture in pixels.
     * @return Null if the picture could not be made.
     */
    public static Drawable thumbnail(Context context, String id, int size) {
        return IconArt.thumbnail(context, id, size);
    }
}
