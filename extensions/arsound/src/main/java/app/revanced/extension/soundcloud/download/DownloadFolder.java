package app.revanced.extension.soundcloud.download;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The folder new tracks are downloaded to, as the settings screen sees it: the default Music/Arsound, or any folder of
 * the phone or its SD card that the user picked with the system's folder picker.
 * <p>
 * Tracks already downloaded stay where they are and keep playing: every track remembers its own folder
 * ({@link DownloadLocation}), so changing the choice only decides where the next downloads go.
 */
public final class DownloadFolder {
    private static final int GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private DownloadFolder() {
    }

    private static String text(String russian, String english) {
        return DownloadTrackPatch.text(russian, english);
    }

    /** The folder new tracks go to when nothing else is chosen, for the user. */
    public static String defaultLabel() {
        return DownloadLocation.DEFAULT.label();
    }

    /** Whether the user left the folder as it is. */
    public static boolean isDefault() {
        return DownloadLocation.current().equals(DownloadLocation.DEFAULT);
    }

    /**
     * Whether a track can be saved to the chosen folder now. False for a card that is out, a folder that was removed
     * and an access that was taken back: tracks then go to the default folder. May ask the system: call off the main thread.
     */
    public static boolean isAvailable(Context context) {
        return DownloadLocation.current().isAvailable(context);
    }

    /** The system's folder picker, opened at the chosen folder (Android 8 and later) or at Music. */
    public static Intent pickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(GRANT | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DownloadLocation current = DownloadLocation.current();
            Uri start = current instanceof TreeFolder
                    ? ((TreeFolder) current).tree()
                    : DocumentsContract.buildDocumentUri(TreeFolder.LOCAL_AUTHORITY, "primary:Music");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
        }
        return intent;
    }

    /**
     * Makes a folder picked with {@link #pickerIntent} the place new tracks go to. Keeps the access to it until the
     * choice changes. Call off the main thread.
     *
     * @return Null once new tracks go to the folder, otherwise what to tell the user.
     */
    public static String choose(Context context, Uri tree) {
        if (tree == null || !DocumentsContract.isTreeUri(tree)) {
            return text("Не удалось выбрать эту папку", "That folder could not be chosen");
        }
        if (!TreeFolder.isLocal(tree)) {
            return text("Эта папка не на телефоне и не на SD-карте: из неё трек не заиграет без интернета. "
                            + "Выберите папку из памяти телефона или карты.",
                    "That folder is not on the phone or an SD card, so a track in it would not play without a connection. "
                            + "Pick a folder of the phone's storage or of a card.");
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.takePersistableUriPermission(tree, GRANT);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not keep the access to " + tree + ": " + ex);
            return text("Не удалось сохранить доступ к папке. Выберите её ещё раз.",
                    "The access to the folder could not be kept. Pick it again.");
        }
        TreeFolder folder = new TreeFolder(tree);
        // The answer about a folder asked a moment ago may be the one from before the access was given.
        TreeFolder.forgetChecks();
        // The default folder picked by hand is the default folder: it keeps the download manager and the tags.
        if (folder.isSameFolderAs(DownloadLocation.DEFAULT)) {
            reset(context);
            return null;
        }
        if (!folder.canWrite(context)) {
            Logger.printInfo(() -> "The folder " + tree + " takes no new files");
            releaseUnused(context);
            return text("В эту папку нельзя записывать файлы. Выберите другую.",
                    "Files cannot be written to that folder. Pick another one.");
        }
        Settings.putString(DownloadLocation.SETTING, folder.id());
        Logger.printInfo(() -> "New downloads go to " + folder.id());
        changed(context);
        return null;
    }

    /** New tracks go to the default folder again. */
    public static void reset(Context context) {
        TreeFolder.forgetChecks();
        Settings.putString(DownloadLocation.SETTING, null);
        Logger.printInfo(() -> "New downloads go to " + DownloadLocation.DEFAULT.id());
        changed(context);
    }

    private static void changed(Context context) {
        releaseUnused(context);
        // The files of the new folder are listed, so the lists know the tracks that are already in it.
        DownloadIndex.refreshAsync();
    }

    /**
     * Lets the system forget the write access to the folders no track is saved in and none is chosen: it keeps a limited
     * number of them. Only the writing is given back; the reading of a folder picked for another purpose stays.
     * Not while a track is on its way: it may be going to the folder that was just left.
     */
    private static void releaseUnused(Context context) {
        if (DownloadProgress.isAnyAssembling()) return;
        try {
            Set<String> used = DownloadTrackPatch.recordedLocationIds();
            used.add(DownloadLocation.current().id());
            ContentResolver resolver = context.getContentResolver();
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                Uri uri = permission.getUri();
                if (!permission.isWritePermission() || !DocumentsContract.isTreeUri(uri)) continue;
                if (used.contains(TreeFolder.PREFIX + uri)) continue;
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Logger.printInfo(() -> "Gave back the write access to " + uri);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not give back unused folder access: " + ex);
        }
    }
}
