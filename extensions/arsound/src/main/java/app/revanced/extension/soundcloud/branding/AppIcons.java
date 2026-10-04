package app.revanced.extension.soundcloud.branding;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

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

    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

    public static final class Icon {
        public final String id;
        public final String name;
        public final String group;

        Icon(String[] row) {
            id = row[0];
            name = RUSSIAN ? row[1] : row[2];
            group = RUSSIAN ? row[3] : row[4];
        }
    }

    private AppIcons() {
    }

    public static List<Icon> all() {
        List<Icon> icons = new ArrayList<>();
        for (String[] row : AppIconList.ICONS) icons.add(new Icon(row));
        return icons;
    }

    private static ComponentName component(Context context, String id) {
        return new ComponentName(context.getPackageName(), ALIAS_PREFIX + id);
    }

    /** The variant whose launcher entry is enabled; the default one if the user never changed it. */
    public static String current(Context context) {
        PackageManager manager = context.getPackageManager();
        for (String[] row : AppIconList.ICONS) {
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
            for (String[] row : AppIconList.ICONS) {
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
}
