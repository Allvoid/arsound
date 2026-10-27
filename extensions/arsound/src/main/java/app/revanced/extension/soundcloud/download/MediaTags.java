package app.revanced.extension.soundcloud.download;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;

import app.revanced.extension.shared.Logger;

/**
 * Gives the rows of saved files in the media store the title and artist of the track, so music players list a
 * track by them instead of by the file name. The files themselves carry no tags.
 * <p>
 * Everything here is best effort: a failure is logged, and the file stays saved and playable.
 */
final class MediaTags {
    private MediaTags() {
    }

    /**
     * Sets the title and artist of a row. Done after the row stopped being pending or was scanned: a scan reads the
     * (empty) tags of the file and would overwrite values set before it.
     *
     * @return True if a row was updated.
     */
    static boolean tag(Context context, Uri row, String title, String artist) {
        ContentValues values = new ContentValues();
        if (title != null && !title.isEmpty()) values.put(MediaStore.Audio.Media.TITLE, title);
        if (artist != null && !artist.isEmpty()) values.put(MediaStore.Audio.Media.ARTIST, artist);
        if (values.size() == 0) return false;
        try {
            int updated = context.getContentResolver().update(row, values, null, null);
            Logger.printInfo(() -> "Tagged " + row + ": " + updated + " row, title=" + title + ", artist=" + artist);
            return updated > 0;
        } catch (Exception ex) {
            // For instance a SecurityException for a row that belongs to another app.
            Logger.printInfo(() -> "Could not tag " + row + ": " + ex);
            return false;
        }
    }

    /**
     * Tags a file that the download manager saved. Its row is made by a scan that the download manager asks for
     * when the download ends, so the row may not be there yet: then the file is scanned here, and tagged when the
     * scan is done.
     */
    static void tagSavedFile(Context context, File file, String title, String artist) {
        if ((title == null || title.isEmpty()) && (artist == null || artist.isEmpty())) return;
        try {
            Uri row = findRow(context, file);
            if (row != null) {
                tag(context, row, title, artist);
                return;
            }
            Logger.printInfo(() -> file.getName() + " has no row in the media store yet, scanning it");
            MediaScannerConnection.scanFile(context, new String[]{file.getPath()}, null, (path, uri) -> {
                if (uri == null) Logger.printInfo(() -> "The scan of " + path + " made no row, not tagged");
                else tag(context, uri, title, artist);
            });
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not tag " + file.getName() + ": " + ex);
        }
    }

    /** The row of a file in the audio collection, or null if the media store does not list it (to this app). */
    private static Uri findRow(Context context, File file) {
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection;
        String[] arguments;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            arguments = new String[]{file.getName(), "%Music/Arsound%"};
        } else {
            selection = MediaStore.MediaColumns.DATA + " = ?";
            arguments = new String[]{file.getPath()};
        }
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[]{MediaStore.Audio.Media._ID}, selection, arguments, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        }
        return null;
    }
}
