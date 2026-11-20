package app.revanced.extension.shared;

import android.content.Context;

import java.io.File;

/**
 * The app's folder on the shared storage ({@code Android/data/<package>/files}), only when it can really be written.
 * <p>
 * On some phones Android cannot make that folder (bot ticket #65, a Xiaomi Mi 9 on a custom Android 16: "Failed to
 * prepare /storage/emulated/0/Android/data/com.arsound.app/files" for every call), and {@link
 * Context#getExternalFilesDir} still returns its path. SoundCloud put its stream cache ({@code exocache}) there: no
 * streamed track could start, only downloaded ones played; the playback log was lost the same way. Callers that fall
 * back to the app's own files when there is no such folder get null here instead of a path that cannot be used.
 */
@SuppressWarnings("unused")
public final class AppStorage {
    private static volatile Boolean usable;
    /**
     * An early "no folder yet" was answered with the app's own files for the stream cache: that stays the answer for the
     * whole run, or the parts of the cache would be split between two folders when the shared one shows up later.
     */
    private static volatile boolean ownForRun;

    private AppStorage() {
    }

    /**
     * Injection point, in place of {@link Context#getExternalFilesDir}: the folder, or null if it cannot be used.
     */
    public static File externalFilesDir(Context context, String type) {
        File folder;
        try {
            folder = context.getExternalFilesDir(type);
        } catch (Exception ex) {
            return null;
        }
        return usable(folder) ? folder : null;
    }

    /**
     * Injection point, in place of {@link Context#getExternalFilesDirs}: the folders, with the app's own files in
     * place of the first one when it cannot be used (callers take the first).
     */
    public static File[] externalFilesDirs(Context context, String type) {
        File[] folders;
        try {
            folders = context.getExternalFilesDirs(type);
        } catch (Exception ex) {
            folders = new File[0];
        }
        if (!ownForRun && folders.length > 0 && usable(folders[0])) return folders;
        if (pinsOwnFolder(folders)) ownForRun = true;
        File own = type == null ? context.getFilesDir() : new File(context.getFilesDir(), type);
        Logger.printInfo(() -> "Storage: the shared app folder cannot be written, using " + own);
        File[] result = folders.length == 0 ? new File[1] : folders.clone();
        result[0] = own;
        return result;
    }

    /** Whether the shared folder of the app was found writable; null until first asked. For the logs. */
    public static Boolean lastAnswer() {
        return usable;
    }

    /**
     * The first real answer is kept for the whole run of the app. A folder that is writable at one call and not at the
     * next would send the stream cache to the shared folder and then to the app's own files: the part written under the
     * other one would be left behind, out of the settings' sizes and out of the clearing. Only "there is no folder yet"
     * (Android gives null early in the start, before the storage is up) is not an answer: it is asked again.
     */
    private static boolean usable(File folder) {
        Boolean first = usable;
        if (first != null) return first;
        Boolean ok = probe(folder);
        if (ok == null) {
            Logger.printInfo(() -> "Storage: the shared app folder is not there yet, asking again later");
            return false;
        }
        usable = ok;
        Logger.printInfo(() -> "Storage: the shared app folder " + folder + (ok ? " is usable" : " cannot be used"));
        return ok;
    }

    /** Whether the answer with no shared folder at all (an early start) must stay the app's own files for the run. */
    static boolean pinsOwnFolder(File[] folders) {
        return folders == null || folders.length == 0 || folders[0] == null;
    }

    /**
     * @return Whether the folder can be written; null when Android gave no folder at all (nothing was tried, so it is no
     * answer to keep).
     */
    static Boolean probe(File folder) {
        if (folder == null) return null;
        return (folder.isDirectory() || folder.mkdirs()) && folder.canWrite();
    }
}
