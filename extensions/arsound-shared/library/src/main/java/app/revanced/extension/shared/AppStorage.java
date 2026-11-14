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
        if (folders.length > 0 && usable(folders[0])) return folders;
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

    private static boolean usable(File folder) {
        boolean ok = folder != null && (folder.isDirectory() || folder.mkdirs()) && folder.canWrite();
        Boolean before = usable;
        usable = ok;
        if (before == null || before != ok) {
            Logger.printInfo(() -> "Storage: the shared app folder " + folder + (ok ? " is usable" : " cannot be used"));
        }
        return ok;
    }
}
