package app.revanced.extension.soundcloud.settings;

import android.content.Context;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * Clears the SoundCloud app data like "Clear data" in Android, but keeps what is worth keeping.
 * <p>
 * The login survives because SoundCloud keeps its tokens in the Android account manager,
 * which lives outside the app data directory. Arsound preference files survive: settings, the
 * downloaded tracks list, the playlist order, local additions and the "Imported" playlist.
 * Only the playlist preload marks are removed, so the emptied database is filled again on the
 * next start instead of a day later.
 */
public final class DataReset {
    private static final String[] KEPT_PREFERENCES_PREFIXES = {"revanced_", "arsound_"};
    private static final String PRELOAD_PREFERENCES = "arsound_playlist_preload.xml";

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
                    if (!isKeptPreference(preference.getName())) delete(preference);
                }
                continue;
            }

            delete(entry);
        }

        Logger.printInfo(() -> "SoundCloud data reset");
    }

    private static boolean isKeptPreference(String name) {
        if (name.equals(PRELOAD_PREFERENCES)) return false;
        for (String prefix : KEPT_PREFERENCES_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
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
