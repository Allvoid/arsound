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

    /**
     * The first answer is kept for the whole run of the app, whoever asks (this method, {@link #externalFilesDirs}, the
     * stream cache, the logs): a folder that is writable at one call and not at the next, or one that is not there yet at
     * an early start and shows up later, would send the stream cache to the shared folder and then to the app's own
     * files, or the other way round. The part written under the other one would be left behind, out of the settings'
     * sizes and out of the clearing. "No folder yet" is an answer too (the own folder), kept the same way.
     * Accepted as it is (20.11): a card that leaves and comes back may move the cache within a run; nothing is lost, the
     * parts are only read from another place.
     */
    private static boolean usable(File folder) {
        // One answer for two threads that ask first at the same time, and no probe (it may make the folder) once it is kept.
        synchronized (AppStorage.class) {
            Boolean first = usable;
            if (first != null) return settle(first, folder == null ? null : Boolean.TRUE);
            Boolean ok = settle(null, probe(folder));
            usable = ok;
            Logger.printInfo(() -> "Storage: the shared app folder " + folder + (ok ? " is usable" : " cannot be used"));
            return ok;
        }
    }

    /**
     * The answer for this call: the first one given when there is one, but never a folder that is not there (a card that
     * came out later, a start before the storage is unlocked: SoundCloud takes the first of the array, a null would fail
     * it); else what the probe found (no folder at all counts as unusable).
     */
    static boolean settle(Boolean kept, Boolean probed) {
        if (kept != null) return kept && probed != null;
        return probed != null && probed;
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
