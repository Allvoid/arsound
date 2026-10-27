package app.revanced.extension.soundcloud.download;

import android.app.DownloadManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * A place where downloaded files are kept. Every track remembers the location its file was saved to
 * ({@link #id()}), so a later change of where new downloads go never loses the tracks saved before it.
 * <p>
 * Everything that writes, finds, lists or removes a downloaded file asks the location, never builds a path
 * itself. There are two kinds. {@link PublicFolder} is a folder of the phone's shared storage that the media store
 * lists (Music/Arsound); the download manager can write there. {@link TreeFolder} is any folder the user picked with
 * the system's folder picker, also one on an SD card; it is reached only through the storage access framework, so
 * the app downloads into it by itself. A kind is recognised in {@link #of(String)} and chosen in {@link #current()};
 * the rest of the download code does not change. A location without paths returns {@code null} from
 * {@link #file(String)}, and lists its files through {@link #list(Context)} like the media store does.
 */
abstract class DownloadLocation {
    /** The location of every track saved before locations were recorded. */
    static final PublicFolder DEFAULT = new PublicFolder(Environment.DIRECTORY_MUSIC, "Arsound");

    /** The setting that holds the {@link #id()} of the location new downloads go to. Missing: the default one. */
    static final String SETTING = "download_folder";

    /** One file of a location, as the location lists it. */
    static final class Row {
        final String locationId;
        final String name;
        final Uri uri;
        final long size;
        final long modifiedMs;
        /** The package that created the file, or null if the system does not say. */
        final String owner;

        Row(String locationId, String name, Uri uri, long size, long modifiedMs, String owner) {
            this.locationId = locationId;
            this.name = name;
            this.uri = uri;
            this.size = size;
            this.modifiedMs = modifiedMs;
            this.owner = owner;
        }

        boolean sameAs(Row other) {
            return uri.equals(other.uri) && size == other.size && modifiedMs == other.modifiedMs;
        }
    }

    /** A file that was written into a location. */
    static final class Saved {
        /** The name it got. It differs from the requested one when that was taken: the media store adds "(1)". */
        final String name;
        /** The address of its row, or null if the location has none (storage before Android 10). */
        final Uri uri;

        Saved(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    interface RowListener {
        /** @param row The row of the file, or null if the system made none. */
        void onRow(Uri row);
    }

    /** Where new downloads go as the user set it, whether or not it can be used right now. */
    static DownloadLocation current() {
        String chosen = Settings.getString(SETTING, null);
        return chosen == null || chosen.isEmpty() ? DEFAULT : of(chosen);
    }

    /** When the user was last told that the chosen folder cannot be used. */
    private static volatile long lostToldAt;

    /**
     * Where a download that starts now goes: the chosen location, or the default one when the chosen cannot be
     * written to (the card is out, the folder is gone, the access was taken back). The choice itself is kept, so the
     * folder is used again as soon as it is back. The user is told, once a minute at most: a playlist asks for
     * the location of every track.
     * <p>
     * May ask the system: call off the main thread.
     */
    static DownloadLocation forNewDownload(Context context) {
        DownloadLocation chosen = current();
        if (chosen.isAvailable(context)) return chosen;
        Logger.printInfo(() -> "The download folder " + chosen.id() + " cannot be used, saving to " + DEFAULT.id());
        long now = SystemClock.elapsedRealtime();
        if (lostToldAt == 0 || now - lostToldAt >= 60_000) {
            lostToldAt = now;
            DownloadTrackPatch.showToast(context, DownloadTrackPatch.text(
                    "Папка «" + chosen.label() + "» недоступна: карта извлечена, папка удалена или доступ отозван. "
                            + "Треки сохраняются в «" + DEFAULT.label() + "». Выберите папку заново в настройках, в разделе «Память».",
                    "The folder " + chosen.label() + " is not available: the card is out, the folder is gone or its access "
                            + "was taken back. Tracks are saved to " + DEFAULT.label() + ". Pick the folder again in the "
                            + "settings, in Storage."));
        }
        return DEFAULT;
    }

    /**
     * The location with this id, as kept in the record of a track; the default one for an id that is missing or
     * not known. A new kind of location is recognised here.
     */
    static DownloadLocation of(String id) {
        if (id == null) return DEFAULT;
        if (id.startsWith(TreeFolder.PREFIX)) {
            TreeFolder tree = TreeFolder.parse(id);
            return tree != null ? tree : DEFAULT;
        }
        PublicFolder folder = PublicFolder.parse(id);
        return folder != null ? folder : DEFAULT;
    }

    /** What the record of a track keeps, and what tells two locations apart. */
    abstract String id();

    /** The name for the user, in the language of the app: "Music/Arsound". */
    abstract String label();

    /**
     * Whether Android's download manager can write the files of this location. If not, the app downloads the file
     * itself and calls {@link #save}.
     */
    abstract boolean usesDownloadManager();

    /** Sets where Android's download manager writes the file; only for a location that {@link #usesDownloadManager}. */
    abstract void setDestination(DownloadManager.Request request, String fileName);

    /**
     * Whether a file can be written to the location now. A folder of the shared storage always can; a folder the user
     * picked cannot once its card is out or its access was taken back. May ask the system: call off the main thread.
     */
    abstract boolean isAvailable(Context context);

    /**
     * Copies a finished file into the location.
     *
     * @param mimeType The type has to match the extension, otherwise the media store appends an extension of its own.
     */
    abstract Saved save(Context context, File source, String fileName, String mimeType) throws IOException;

    /**
     * The files in the location that the app can see. Reads the media store: call off the main thread.
     *
     * @return The files, or null if the location cannot be listed now (a card that is out): what was known of it stays.
     */
    abstract List<Row> list(Context context);

    /** The row of the file with this name, asked of the system now, or null if it lists none to this app. */
    abstract Uri findRow(Context context, String fileName);

    /** Makes the system list a file that was written outside of it (by the download manager), and reports its row. */
    abstract void scan(Context context, String fileName, RowListener listener);

    /** The file as a path, or null if the location has none. The app may still be unable to read it. */
    abstract File file(String fileName);

    @Override
    public boolean equals(Object other) {
        return other instanceof DownloadLocation && ((DownloadLocation) other).id().equals(id());
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    /**
     * Removes the file of a row, whichever kind of location it is in: a media store row is deleted through the media
     * store, a document of a picked folder through its document provider.
     *
     * @return True if the file was removed. False if the system refused, or the file was not there.
     */
    static boolean deleteRow(Context context, Uri row) {
        try {
            if (MediaStore.AUTHORITY.equals(row.getAuthority())) return context.getContentResolver().delete(row, null, null) > 0;
            return DocumentsContract.deleteDocument(context.getContentResolver(), row);
        } catch (Exception ex) {
            // For instance a SecurityException for a row that belongs to another install.
            Logger.printInfo(() -> "Could not delete " + row + ": " + ex);
            return false;
        }
    }

    static void copy(File source, OutputStream output) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream target = output) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) target.write(buffer, 0, read);
        }
    }

    /**
     * A folder of the shared storage, for example Music/Arsound. Files are written through the media store, which
     * since Android 10 is the only way for an app to put a file there.
     */
    static final class PublicFolder extends DownloadLocation {
        private final String directory;
        private final String subFolder;

        PublicFolder(String directory, String subFolder) {
            this.directory = directory;
            this.subFolder = subFolder;
        }

        /** The folder with this id, or null if the id is not a folder. */
        static PublicFolder parse(String id) {
            int slash = id.indexOf('/');
            if (slash <= 0) return null;
            String sub = id.substring(slash + 1);
            while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
            return sub.isEmpty() ? null : new PublicFolder(id.substring(0, slash), sub);
        }

        /** The path inside the storage, without a slash at the end: "Music/Arsound". */
        String path() {
            return directory + "/" + subFolder;
        }

        /** The path inside the storage, with the slash the media store keeps: "Music/Arsound/". */
        @Override
        String id() {
            return directory + "/" + subFolder + "/";
        }

        @Override
        String label() {
            String name = Environment.DIRECTORY_MUSIC.equals(directory) ? DownloadTrackPatch.text("Музыка", "Music") : directory;
            return name + "/" + subFolder;
        }

        private Uri collection() {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        @Override
        boolean usesDownloadManager() {
            return true;
        }

        @Override
        void setDestination(DownloadManager.Request request, String fileName) {
            request.setDestinationInExternalPublicDir(directory, subFolder + "/" + fileName);
        }

        @Override
        boolean isAvailable(Context context) {
            return true;
        }

        @Override
        File file(String fileName) {
            return new File(new File(Environment.getExternalStoragePublicDirectory(directory), subFolder), fileName);
        }

        @Override
        Saved save(Context context, File source, String fileName, String mimeType) throws IOException {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File target = file(fileName);
                File folder = target.getParentFile();
                if (target.exists() && !target.delete()) Logger.printInfo(() -> "Could not replace " + target);
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException(label() + " could not be created");
                copy(source, new FileOutputStream(target));
                return new Saved(fileName, null);
            }

            // No look at the file first: since Android 10 an app sees but cannot remove files it did not just
            // create itself, and the name is made free by the caller. A name taken after all gets a "(1)".
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/" + subFolder);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = context.getContentResolver().insert(collection(), values);
            if (uri == null) throw new IOException("The file could not be created in " + id());
            try {
                OutputStream output = context.getContentResolver().openOutputStream(uri);
                if (output == null) throw new IOException("The file could not be opened");
                copy(source, output);

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
            } catch (Exception ex) {
                context.getContentResolver().delete(uri, null, null);
                if (ex instanceof IOException) throw (IOException) ex;
                throw new IOException(ex);
            }
            String saved = savedName(context, uri);
            if (saved != null && !saved.equals(fileName)) {
                Logger.printInfo(() -> "Saved as " + saved + ": " + fileName + " was taken in " + id());
                return new Saved(saved, uri);
            }
            return new Saved(fileName, uri);
        }

        /** The name the media store gave the file, or null if it cannot be asked. */
        private static String savedName(Context context, Uri uri) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not read the name of " + uri + ": " + ex);
                return null;
            }
        }

        @Override
        List<Row> list(Context context) {
            List<Row> rows = new ArrayList<>();
            // Before Android 10 there is no folder column, and the files are found by their path instead.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return rows;
            Uri collection = collection();
            // The media store keeps the folder with a trailing slash; both forms are asked to be safe.
            String selection = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR "
                    + MediaStore.MediaColumns.RELATIVE_PATH + " = ?) AND " + MediaStore.MediaColumns.IS_PENDING + " = 0";
            String[] arguments = {id(), directory + "/" + subFolder};
            try (Cursor cursor = context.getContentResolver().query(collection, new String[]{
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            }, selection, arguments, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (name == null) continue;
                    // The modification time is in seconds.
                    rows.add(new Row(id(), name, ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            cursor.getLong(2), cursor.getLong(3) * 1000L, cursor.getString(4)));
                }
            }
            return rows;
        }

        @Override
        Uri findRow(Context context, String fileName) {
            Uri collection = collection();
            String selection;
            String[] arguments;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND ("
                        + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?)";
                arguments = new String[]{fileName, id(), directory + "/" + subFolder};
            } else {
                selection = MediaStore.MediaColumns.DATA + " = ?";
                arguments = new String[]{file(fileName).getPath()};
            }
            try (Cursor cursor = context.getContentResolver().query(collection,
                    new String[]{MediaStore.MediaColumns._ID}, selection, arguments, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not look up " + fileName + " in the media store: " + ex);
            }
            return null;
        }

        @Override
        void scan(Context context, String fileName, RowListener listener) {
            MediaScannerConnection.scanFile(context, new String[]{file(fileName).getPath()}, null,
                    (path, uri) -> listener.onRow(uri));
        }
    }
}
