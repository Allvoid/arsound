package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;

/** Looking up SoundCloud's resources by name. */
public final class AppResources {
    /** The resources keep SoundCloud's package name although the app may be installed under another one. */
    public static final String SOUNDCLOUD_PACKAGE = "com.soundcloud.android";

    private AppResources() {
    }

    /**
     * The id of a resource, found under the package the app is installed as and then under SoundCloud's own;
     * 0 when neither has it.
     *
     * @param type The resource type: "id", "drawable", "color", "style", "attr", "font", "raw".
     */
    public static int identifier(Context context, String name, String type) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, type, context.getPackageName());
        return id != 0 ? id : resources.getIdentifier(name, type, SOUNDCLOUD_PACKAGE);
    }

    /** The colour the theme of the context gives to a theme attribute (as an id), or the fallback if it gives none. */
    public static int themeColor(Context context, int attribute, int fallback) {
        TypedArray array = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return array.getColor(0, fallback);
        } finally {
            array.recycle();
        }
    }
}
