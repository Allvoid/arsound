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
import android.system.Os;
import android.system.StructStatVfs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Any folder of the phone or of its SD card that the user picked with the system's folder picker.
 * <p>
 * Android lets an app write there only through the storage access framework, and only to the folder it was given
 * and what is inside it. The download manager cannot write through it, so tracks are downloaded by the app itself
 * (see {@link ProgressiveDownloader} and {@link HlsDownloader}) and then copied in. A file has no path the app may
 * use: it is read, listed and removed by the address of its document, and the player is given that address.
 * <p>
 * A file in a picked folder has no row in the media store that the app may tag, so the title and artist of a track are
 * written into the file itself while it is copied in ({@link FileTags}).
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

    /** The last answer of {@link #isAvailable}, kept as one value so that a reader never sees half of a newer one. */
    private static final class Checked {
        final String id;
        final long at;
        final boolean available;

        Checked(String id, long at, boolean available) {
            this.id = id;
            this.at = at;
            this.available = available;
        }
    }

    private static volatile Checked checked;

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

    @Override
    Uri pickerStart() {
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

    @Override
    String documentId() {
        return rootDocumentId;
    }

    /** "Music/Foo" for the phone's storage, "SD card/Podcasts" for a card: the path inside the storage. */
    @Override
    String label() {
        String key = tree.toString();
        String label = LABELS.get(key);
        if (label != null) return label;

        // The document id of the folder is "volume:path": "primary:Music/Foo", "1A2B-3C4D:Podcasts".
        int colon = rootDocumentId.indexOf(':');
        String volume = colon > 0 ? rootDocumentId.substring(0, colon) : rootDocumentId;
        String path = colon >= 0 ? rootDocumentId.substring(colon + 1) : "";
        // The default folder is shown with "Музыка"; the same folder picked by hand is shown the same way.
        String music = Environment.DIRECTORY_MUSIC;
        if (path.equals(music) || path.startsWith(music + "/")) {
            path = Lang.text("Музыка", "Music") + path.substring(music.length());
        }
        if ("primary".equals(volume)) {
            label = path.isEmpty() ? Lang.text("Память телефона", "Phone storage") : path;
            LABELS.put(key, label);
            return label;
        }
        String storage = storageName(volume);
        label = (storage != null ? storage : Lang.text("SD-карта", "SD card") + " " + volume)
                + (path.isEmpty() ? "" : "/" + path);
        // A card the system does not name now (it is out) is named by its id; the real name is looked for again later.
        if (storage != null) LABELS.put(key, label);
        return label;
    }

    /** The name the system gives to the storage with this id ("SD card"), or null if it does not say. */
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
        return null;
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
        Checked last = checked;
        if (last != null && last.id.equals(id) && now - last.at < AVAILABLE_CHECK_MS) return last.available;
        boolean available = canWrite(context);
        checked = new Checked(id, now, available);
        return available;
    }

    /** Forgets the last answer of {@link #isAvailable}: the access to a folder was just given or taken back. */
    static void forgetChecks() {
        checked = null;
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
    boolean holdsOtherFiles() {
        return true;
    }

    @Override
    Saved save(Context context, File source, String fileName, String mimeType, String title, String artist)
            throws IOException {
        // The tags go into the bytes that are copied: the file has no row to tag. A file whose layout is not
        // understood, or that cannot be read for planning, is copied as it is.
        FileTags.Tagged tagged = null;
        try {
            tagged = FileTags.plan(source, fileName, title, artist);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not plan the tags of " + fileName + ": " + ex);
        }
        Writing writing = startFile(context, fileName, mimeType);
        try {
            if (tagged != null) {
                try (OutputStream output = writing.stream()) {
                    tagged.writeTo(output);
                }
            } else {
                copy(source, writing.stream());
            }
            return writing.finish();
        } catch (Exception ex) {
            writing.abort();
            if (isFull(ex)) throw new HlsDownloader.NoSpaceException("No room left in " + label());
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException(ex);
        }
    }

    @Override
    Writing startFile(Context context, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        // A folder lists a file at once, half written and under its final name, and every music app shows it as a
        // broken track. When the process is killed in the middle (the task killer of some makers, a locked phone), nothing
        // would ever remove it. So the file is written under a hidden name and is named only when it is whole.
        Uri part = null;
        try {
            part = DocumentsContract.createDocument(resolver, rootDocument(), UnfinishedParts.MIME, UnfinishedParts.newName());
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not create a hidden file in " + label() + ", writing under the final name: " + ex);
        }
        if (part != null) {
            if (supportsRename(resolver, part)) return new TreeFile(resolver, part, fileName, true);
            // A provider that cannot rename keeps the file under its final name, as before.
            removeQuietly(resolver, part);
        }

        Uri created;
        try {
            // The provider gives a taken name a "(1)" of its own, so nothing is overwritten.
            created = DocumentsContract.createDocument(resolver, rootDocument(), mimeType, fileName);
        } catch (Exception ex) {
            throw new IOException("The file could not be created in " + label() + ": " + ex, ex);
        }
        if (created == null) throw new IOException("The file could not be created in " + label());
        return new TreeFile(resolver, created, fileName, false);
    }

    /** Whether the provider can give the document another name. */
    private static boolean supportsRename(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document, new String[]{Document.COLUMN_FLAGS}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && (cursor.getInt(0) & Document.FLAG_SUPPORTS_RENAME) != 0;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not ask if " + document + " can be renamed: " + ex);
            return false;
        }
    }

    private static void removeQuietly(ContentResolver resolver, Uri document) {
        try {
            DocumentsContract.deleteDocument(resolver, document);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not remove " + document + ": " + ex);
        }
    }

    /**
     * Removes the hidden files that earlier runs of the app left behind when the process was killed in the middle of a
     * download. Run at the start of the app and when the folder changes. Those of downloads that run now are left alone.
     * Call off the main thread.
     */
    static void sweepUnfinished(Context context) {
        UnfinishedParts.sweep(context);
    }

    /** A file of the folder that is being written. */
    private final class TreeFile implements Writing {
        private final ContentResolver resolver;
        /** Changes once, when the file is named: the provider may give it another address with the name. */
        private Uri document;
        private final String requestedName;
        /** Whether the file has its hidden name still, and is named by {@link #finish}. */
        private boolean hidden;
        private OutputStream output;

        TreeFile(ContentResolver resolver, Uri document, String requestedName, boolean hidden) throws IOException {
            this.resolver = resolver;
            this.document = document;
            this.requestedName = requestedName;
            this.hidden = hidden;
            if (hidden) UnfinishedParts.note(document);
            try {
                output = open("w");
            } catch (IOException | RuntimeException ex) {
                // Unlike the media store, a folder lists a file that is half written: it must not stay.
                abort();
                throw ex;
            }
        }

        private OutputStream open(String mode) throws IOException {
            OutputStream stream = resolver.openOutputStream(document, mode);
            if (stream == null) throw new IOException("The file could not be opened");
            return stream;
        }

        @Override
        public OutputStream stream() {
            return output;
        }

        @Override
        public OutputStream restart() throws IOException {
            closeQuietly();
            output = open("wt");
            return output;
        }

        @Override
        public long freeBytes() {
            try {
                if (output instanceof FileOutputStream) {
                    StructStatVfs stat = Os.fstatvfs(((FileOutputStream) output).getFD());
                    return stat.f_bavail * stat.f_frsize;
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not ask for the free room in " + label() + ": " + ex);
            }
            return -1;
        }

        @Override
        public Saved finish() throws IOException {
            output.close();
            if (hidden) {
                // The provider may change the address of the file with its name, so the one it returns is the one kept.
                Uri named;
                try {
                    named = DocumentsContract.renameDocument(resolver, document, requestedName);
                } catch (Exception ex) {
                    throw new IOException("The file could not be named in " + label() + ": " + ex, ex);
                }
                if (named == null) throw new IOException("The file could not be named in " + label());
                UnfinishedParts.drop(document);
                document = named;
                hidden = false;
            }
            String saved = displayName(resolver, document);
            if (saved != null && !saved.equals(requestedName)) {
                Logger.printInfo(() -> "Saved as " + saved + ": " + requestedName + " was taken in " + label());
                return new Saved(saved, document);
            }
            return new Saved(requestedName, document);
        }

        @Override
        public void abort() {
            closeQuietly();
            try {
                DocumentsContract.deleteDocument(resolver, document);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not remove the unfinished " + document + ": " + ex);
            }
            if (hidden) UnfinishedParts.drop(document);
        }

        private void closeQuietly() {
            try {
                if (output != null) output.close();
            } catch (IOException ignored) {
                // Closing a stream that already failed says nothing new.
            }
        }
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
