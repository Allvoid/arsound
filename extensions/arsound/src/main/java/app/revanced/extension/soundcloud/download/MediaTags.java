package app.revanced.extension.soundcloud.download;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import app.revanced.extension.shared.Logger;

/**
 * Gives the rows of saved files in the media store the title and artist of the track, so music players list a
 * track by them instead of by the file name. The files themselves carry no tags. A file in a folder the user picked has
 * no such row, and gets its tags written into the file by {@link FileTags}.
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
        // A file in another kind of location (a document of a folder picked by the user) has no row to tag.
        if (!MediaStore.AUTHORITY.equals(row.getAuthority())) return false;
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
     * Finds the row of a file that the download manager saved, and tags it. Its row is made by a scan that the
     * download manager asks for when the download ends, so the row may not be there yet: then the file is scanned
     * here, and tagged when the scan is done.
     *
     * @param found Told the row once there is one, whether or not there is anything to tag; null if the scan made none.
     */
    static void tagSavedFile(Context context, DownloadLocation location, String fileName, String title, String artist,
                             DownloadLocation.RowListener found) {
        try {
            Uri row = location.findRow(context, fileName);
            if (row != null) {
                found.onRow(row);
                tag(context, row, title, artist);
                return;
            }
            Logger.printInfo(() -> fileName + " has no row in the media store yet, scanning it");
            location.scan(context, fileName, scanned -> {
                if (scanned == null) {
                    Logger.printInfo(() -> "The scan of " + fileName + " made no row, not tagged");
                    return;
                }
                found.onRow(scanned);
                tag(context, scanned, title, artist);
            });
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not tag " + fileName + ": " + ex);
        }
    }
}
