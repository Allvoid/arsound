package app.revanced.extension.soundcloud.download;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Any folder of the phone or of its SD card that the user picked with the system's folder picker.
 * <p>
 * Android lets an app write there only through the storage access framework, and only to the folder it was given
 * and what is inside it. The download manager cannot write through it, so tracks are downloaded by the app itself
 * (see {@link ProgressiveDownloader} and {@link HlsDownloader}) and then copied in. A file has no path the app may
 * use: it is read, listed and removed by the address of its document, and the player is given that address.
 * <p>
 * The access is kept by the system until the app lets it go ({@link DownloadFolder}), and it is lost when the folder
 * is removed, the card is taken out or the user revokes it. The location then stays known, so its tracks are not
 * forgotten, but {@link #isAvailable} says no and new downloads go to the default folder.
 */
final class TreeFolder extends DownloadLocation {
    /** Starts the id of a location of this kind; the rest is the address of the folder. */
    static final String PREFIX = "tree:";
    /**
     * The provider of the phone's own storage, its SD cards and USB sticks. The folders of other providers (a cloud
     * drive, an app) are not on the phone: a track saved there would not play without a connection.
     */
    static final String LOCAL_AUTHORITY = "com.android.externalstorage.documents";

    /** How long the answer of {@link #isAvailable} stays good: a playlist asks for every track it starts. */
    private static final long AVAILABLE_CHECK_MS = 5_000;
    private static final Map<String, String> LABELS = new ConcurrentHashMap<>();
    private static volatile String checkedId;
    private static volatile long checkedAt;
    private static volatile boolean checkedAvailable;

    private final Uri tree;
    private final String rootDocumentId;

    TreeFolder(Uri tree) {
        this.tree = tree;
        this.rootDocumentId = DocumentsContract.getTreeDocumentId(tree);
    }

    /** The folder with this id, or null if the id is not the address of a picked folder. */
    static TreeFolder parse(String id) {
        if (!id.startsWith(PREFIX)) return null;
        try {
            Uri tree = Uri.parse(id.substring(PREFIX.length()));
            return DocumentsContract.isTreeUri(tree) ? new TreeFolder(tree) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Whether a picked folder is on the phone's own storage, so that its tracks play without a connection. */
    static boolean isLocal(Uri tree) {
        return LOCAL_AUTHORITY.equals(tree.getAuthority());
    }

    Uri tree() {
        return tree;
    }

    /** Whether this is the same folder of the phone's storage as a folder of the shared storage. */
    boolean isSameFolderAs(PublicFolder folder) {
        return rootDocumentId.equalsIgnoreCase("primary:" + folder.path());
    }

    @Override
    String id() {
        return PREFIX + tree;
    }

    /** "Music/Foo" for the phone's storage, "SD card/Podcasts" for a card: the path inside the storage. */
    @Override
    String label() {
        String key = tree.toString();
        String label = LABELS.get(key);
        if (label == null) {
            label = describe();
            LABELS.put(key, label);
        }
        return label;
    }

    private String describe() {
        // The document id of the folder is "volume:path": "primary:Music/Foo", "1A2B-3C4D:Podcasts".
        int colon = rootDocumentId.indexOf(':');
        String volume = colon > 0 ? rootDocumentId.substring(0, colon) : rootDocumentId;
        String path = colon >= 0 ? rootDocumentId.substring(colon + 1) : "";
        // The default folder is shown with "Музыка"; the same folder picked by hand is shown the same way.
        String music = Environment.DIRECTORY_MUSIC;
        if (path.equals(music) || path.startsWith(music + "/")) {
            path = DownloadTrackPatch.text("Музыка", "Music") + path.substring(music.length());
        }
        if ("primary".equals(volume)) {
            return path.isEmpty() ? DownloadTrackPatch.text("Память телефона", "Phone storage") : path;
        }
        String storage = storageName(volume);
        return path.isEmpty() ? storage : storage + "/" + path;
    }

    /** The name the system gives to the storage with this id ("SD card"), or the id if it does not say. */
    private static String storageName(String volumeId) {
        try {
            Context context = Utils.getContext();
            StorageManager manager = context == null ? null : context.getSystemService(StorageManager.class);
            if (manager != null) {
                for (StorageVolume volume : manager.getStorageVolumes()) {
                    if (!volumeId.equalsIgnoreCase(volume.getUuid())) continue;
                    String description = volume.getDescription(context);
                    if (description != null && !description.isEmpty()) return description;
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not name the storage " + volumeId + ": " + ex);
        }
        return DownloadTrackPatch.text("SD-карта", "SD card") + " " + volumeId;
    }

    /** The address of the folder itself as a document. */
    private Uri rootDocument() {
        return DocumentsContract.buildDocumentUriUsingTree(tree, rootDocumentId);
    }

    @Override
    boolean usesDownloadManager() {
        return false;
    }

    @Override
    void setDestination(DownloadManager.Request request, String fileName) {
        throw new UnsupportedOperationException("The download manager cannot write to " + id());
    }

    @Override
    boolean isAvailable(Context context) {
        String id = id();
        long now = SystemClock.elapsedRealtime();
        if (id.equals(checkedId) && now - checkedAt < AVAILABLE_CHECK_MS) return checkedAvailable;
        boolean available = canWrite(context);
        checkedAvailable = available;
        checkedAt = now;
        checkedId = id;
        return available;
    }

    /**
     * Whether the app still holds the access to read and write the folder, and the folder is there and takes new
     * files. Asked of the system each time.
     */
    boolean canWrite(Context context) {
        ContentResolver resolver = context.getContentResolver();
        boolean granted = false;
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission() && permission.isWritePermission()) {
                granted = true;
                break;
            }
        }
        if (!granted) return false;
        try (Cursor cursor = resolver.query(rootDocument(), new String[]{Document.COLUMN_FLAGS}, null, null, null)) {
            // No row: the provider could not find the folder (it was removed, or its card is out).
            return cursor != null && cursor.moveToFirst() && (cursor.getInt(0) & Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not ask for " + id() + ": " + ex);
            return false;
        }
    }

    @Override
    File file(String fileName) {
        return null;
    }

    @Override
    Saved save(Context context, File source, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri created;
        try {
            // The provider gives a taken name a "(1)" of its own, so nothing is overwritten.
            created = DocumentsContract.createDocument(resolver, rootDocument(), mimeType, fileName);
        } catch (Exception ex) {
            throw new IOException("The file could not be created in " + label() + ": " + ex, ex);
        }
        if (created == null) throw new IOException("The file could not be created in " + label());
        try {
            OutputStream output = resolver.openOutputStream(created, "w");
            if (output == null) throw new IOException("The file could not be opened");
            copy(source, output);
        } catch (Exception ex) {
            // Unlike the media store, a folder lists a file that is half written: it must not stay.
            DocumentsContract.deleteDocument(resolver, created);
            // A full card says only "ENOSPC" in a message that differs between systems.
            if (String.valueOf(ex.getMessage()).contains("ENOSPC")) {
                throw new HlsDownloader.NoSpaceException("No room left in " + label());
            }
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException(ex);
        }
        String saved = displayName(resolver, created);
        if (saved != null && !saved.equals(fileName)) {
            Logger.printInfo(() -> "Saved as " + saved + ": " + fileName + " was taken in " + label());
            return new Saved(saved, created);
        }
        return new Saved(fileName, created);
    }

    /** The name the provider gave the file, or null if it cannot be asked. */
    private static String displayName(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the name of " + document + ": " + ex);
            return null;
        }
    }

    @Override
    List<Row> list(Context context) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootDocumentId);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            // No cursor: the provider could not open the folder.
            if (cursor == null) return null;
            List<Row> rows = new ArrayList<>();
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || Document.MIME_TYPE_DIR.equals(cursor.getString(4))) continue;
                rows.add(new Row(id(), name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        cursor.getLong(2), cursor.getLong(3), null));
            }
            return rows;
        } catch (Exception ex) {
            // For instance a SecurityException once the access was taken back.
            Logger.printInfo(() -> "Could not list " + id() + ": " + ex);
            return null;
        }
    }

    @Override
    Uri findRow(Context context, String fileName) {
        // A folder cannot be asked for one name, so it is listed.
        List<Row> rows = list(context);
        if (rows == null) return null;
        for (Row row : rows) if (row.name.equals(fileName)) return row.uri;
        return null;
    }

    @Override
    void scan(Context context, String fileName, RowListener listener) {
        // A file is written through the provider, which lists it at once: nothing is scanned.
        listener.onRow(findRow(context, fileName));
    }
}
