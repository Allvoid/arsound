package app.revanced.extension.soundcloud.branding;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import app.revanced.extension.shared.Logger;

/**
 * Which launcher entry is enabled. Every icon variant is its own entry (an activity alias of SoundCloud's start
 * screen), and only one of them is enabled. SoundCloud's own icon switch disables the start screen itself, which also
 * breaks the app restart and links that open it, so the start screen keeps no launcher entry and is never disabled.
 */
final class LauncherAlias {
    /** Must match the alias names the branding patch adds to the manifest. */
    static final String ALIAS_PREFIX = "com.soundcloud.android.launcher.ArsoundIcon_";

    private LauncherAlias() {
    }

    private static ComponentName component(Context context, String id) {
        return new ComponentName(context.getPackageName(), ALIAS_PREFIX + id);
    }

    /** The variant whose launcher entry is enabled; the default one if the user never changed it. */
    static String current(Context context) {
        PackageManager manager = context.getPackageManager();
        for (String[] row : IconCatalog.rows()) {
            try {
                if (manager.getComponentEnabledSetting(component(context, row[0]))
                        == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return row[0];
                }
            } catch (Exception ignored) {
                // An alias missing from the manifest.
            }
        }
        return IconCatalog.defaultId();
    }

    /**
     * Shows another icon. The new entry is enabled before the old one is disabled, so the app always has one.
     * The launcher redraws within a few seconds; some launchers drop the shortcut from the home screen.
     */
    static boolean apply(Context context, String id) {
        try {
            PackageManager manager = context.getPackageManager();
            manager.setComponentEnabledSetting(component(context, id),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (String[] row : IconCatalog.rows()) {
                if (row[0].equals(id)) continue;
                ComponentName other = component(context, row[0]);
                int state = manager.getComponentEnabledSetting(other);
                // The default entry is enabled by the manifest, so it is switched off explicitly.
                boolean enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && row[0].equals(IconCatalog.defaultId()));
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
}
