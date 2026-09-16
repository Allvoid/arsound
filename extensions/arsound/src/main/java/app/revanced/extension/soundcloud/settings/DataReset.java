package app.revanced.extension.soundcloud.settings;

import android.content.Context;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * Clears the SoundCloud app data like "Clear data" in Android, but keeps what is worth keeping.
 * <p>
 * The login survives because SoundCloud keeps its tokens in the Android account manager,
 * which lives outside the app data directory. The Arsound settings and the downloaded tracks list
 * survive because their preference files are skipped.
 */
public final class DataReset {
    private static final String KEPT_PREFERENCES_PREFIX = "revanced_";

    /**
     * Folders the app needs to run, which are rebuilt by Android and must not be removed.
     */
    private static final String[] KEPT_FOLDERS = {"lib", "code_cache", "app_revanced"};

    public static void resetKeepingLogin(Context context) {
        File dataDir = context.getDataDir();
        File[] entries = dataDir.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (isKeptFolder(entry.getName())) continue;

            if (entry.getName().equals("shared_prefs")) {
                File[] preferences = entry.listFiles();
                if (preferences == null) continue;

                for (File preference : preferences) {
                    if (!preference.getName().startsWith(KEPT_PREFERENCES_PREFIX)) delete(preference);
                }
                continue;
            }

            delete(entry);
        }

        Logger.printInfo(() -> "SoundCloud data reset");
    }

    private static boolean isKeptFolder(String name) {
        for (String kept : KEPT_FOLDERS) {
            if (kept.equals(name)) return true;
        }
        return false;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        if (!file.delete()) {
            Logger.printDebug(() -> "Could not delete " + file);
        }
    }
}
