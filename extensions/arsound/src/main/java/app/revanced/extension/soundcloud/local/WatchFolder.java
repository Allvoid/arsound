package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadIndex;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * A folder on the phone chosen by the user: audio files that appear in it are imported by
 * themselves, the same way as files picked by hand. Checked when the app comes to the screen, at
 * most once a minute, and from the settings.
 * <p>
 * Files already seen are remembered by their document id, so a file removed from the imported
 * music is not imported again while it stays in the folder.
 */
@SuppressWarnings("unused")
public final class WatchFolder {
    private static final String PREFERENCES_NAME = "arsound_watch_folder";
    private static final String ENABLED = "enabled";
    private static final String FOLDER = "folder";
    private static final String SEEN = "seen";
    private static final long CHECK_INTERVAL_MS = 60_000;
    /** How long a file must stay untouched before it is imported. */
    private static final long SETTLE_MS = 15_000;
    private static final int MAX_DEPTH = 4;
    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma"};

    private static volatile long lastCheck;
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    /** Document id to its size at the previous check, for files of a provider that does not tell when they changed. */
    private static final Map<String, Long> PENDING_SIZES = new ConcurrentHashMap<>();

    private WatchFolder() {
    }

    /** The failed copies of each file, by document id: {"id": count}. */
    private static final String FAILURES = "watch_folder_failures";
    private static final int MAX_FAILURES = 3;
    /** Below this much free space the folder is not copied from at all. */
    private static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

    private static org.json.JSONObject readFailures(SharedPreferences preferences) {
        try {
            return new org.json.JSONObject(preferences.getString(FAILURES, "{}"));
        } catch (Exception ex) {
            return new org.json.JSONObject();
        }
    }

    private static SharedPreferences preferences() {
        return LocalPrefs.open(PREFERENCES_NAME);
    }

    public static boolean isEnabled() {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.getBoolean(ENABLED, false) && preferences.getString(FOLDER, null) != null;
    }

    public static void setEnabled(boolean enabled) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putBoolean(ENABLED, enabled).apply();
    }

    public static Uri getFolder() {
        SharedPreferences preferences = preferences();
        String folder = preferences == null ? null : preferences.getString(FOLDER, null);
        return folder == null ? null : Uri.parse(folder);
    }

    /** A readable name of the chosen folder, or null. */
    public static String folderName() {
        Uri folder = getFolder();
        if (folder == null) return null;
        String id = DocumentsContract.getTreeDocumentId(folder);
        // "primary:Music/Arsound" is shown as "Music/Arsound".
        int colon = id.indexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }

    /** Called after the user picked a folder: the files already in it are imported as well. */
    static void setFolder(Context context, Uri folder) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putString(FOLDER, folder.toString()).remove(SEEN).putBoolean(ENABLED, true).apply();
        PENDING_SIZES.clear();
        Logger.printInfo(() -> "Watch folder set: " + folder);
        check(context, true);
    }

    /** Injection point through the activity callbacks. */
    public static void onActivityResumed(Activity activity) {
        if (!isEnabled() || System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return;
        check(activity.getApplicationContext(), false);
    }

    /**
     * Imports the audio files of the folder that were not seen before. Runs in the background.
     *
     * @param report Shows a message even if nothing new was found.
     */
    public static void check(Context context, boolean report) {
        Uri folder = getFolder();
        if (folder == null || !CHECKING.compareAndSet(false, true)) return;
        lastCheck = System.currentTimeMillis();
        Utils.runOnBackgroundThread(() -> {
            try {
                SharedPreferences preferences = preferences();
                Set<String> seen = new HashSet<>(preferences.getStringSet(SEEN, new HashSet<>()));
                // Files imported by hand earlier have the same name and size: they are not copied twice.
                Set<String> imported = new HashSet<>();
                for (File file : LocalMusic.getFiles(context)) imported.add(file.getName() + '/' + file.length());
                // The tracks the app downloaded, and the folders it downloads to, when they lie in the watched folder:
                // they are tracks already, and would be copied a second time as new files.
                Set<String> downloads = new HashSet<>(DownloadIndex.downloadedDocumentIds());
                downloads.addAll(DownloadIndex.downloadFolderDocumentIds());
                Scan scan = new Scan(context.getContentResolver(), folder, seen, imported, downloads);
                scan.collect(DocumentsContract.getTreeDocumentId(folder), 0);
                List<Uri> found = scan.found;
                List<String> foundIds = scan.foundIds;

                // One by one, to know which file failed: only that one is tried again.
                List<File> files = new ArrayList<>();
                Set<String> failed = new HashSet<>();
                Set<String> skipped = new HashSet<>();
                org.json.JSONObject failures = readFailures(preferences);
                for (Uri uri : found) {
                    String id = DocumentsContract.getDocumentId(uri);
                    // A nearly full phone: nothing is copied, and nothing counts as a failure of the file.
                    if (context.getFilesDir().getUsableSpace() < MIN_FREE_BYTES) {
                        skipped.add(id);
                        continue;
                    }
                    List<File> copied = LocalMusic.importFileList(context, Collections.singletonList(uri));
                    if (copied.isEmpty()) {
                        int count = failures.optInt(id) + 1;
                        // A file that fails again and again is given up on: it was copied at every check.
                        if (count < MAX_FAILURES) failed.add(id);
                        else Logger.printInfo(() -> "Watch folder: gave up on " + id + " after " + count + " failures");
                        failures.put(id, count);
                    } else {
                        failures.remove(id);
                    }
                    files.addAll(copied);
                }
                seen.addAll(foundIds);
                // A file that could not be copied is tried again at the next check, not forgotten.
                seen.removeAll(failed);
                seen.removeAll(skipped);
                preferences.edit().putStringSet(SEEN, seen).putString(FAILURES, failures.toString()).apply();
                Logger.printInfo(() -> "Watch folder: " + found.size() + " new, imported " + files.size());
                if (files.isEmpty() && !report) return;

                LocalMusic.onFileAdded();
                String saved = SavedPlaylist.getUrn();
                Utils.runOnMainThread(() -> {
                    if (saved != null) PlaylistChanges.playlistChanged(saved);
                    Toast.makeText(context, files.isEmpty()
                            ? text("В папке нет новых треков", "No new tracks in the folder")
                            : text("Импортировано из папки: ", "Imported from the folder: ") + files.size(), Toast.LENGTH_SHORT).show();
                });
            } catch (SecurityException ex) {
                // The folder was removed or its access was taken back.
                Logger.printInfo(() -> "Watch folder is not readable: " + ex);
                if (report) Toasts.showLongOnMainThread(context,
                        text("Нет доступа к папке — выберите её заново", "No access to the folder, pick it again"));
            } catch (Exception ex) {
                Logger.printException(() -> "Watch folder check failure", ex);
            } finally {
                CHECKING.set(false);
            }
        });
    }

    /** One walk through the watched folder: what is new in it, and what the walk needs to tell what is new. */
    private static final class Scan {
        private final ContentResolver resolver;
        private final Uri tree;
        /** Document ids seen at an earlier check. */
        private final Set<String> seen;
        /** Name and size of the files in the imported music. */
        private final Set<String> imported;
        /** Document ids of the app's own downloads and download folders. */
        private final Set<String> downloads;
        /** The new files. */
        final List<Uri> found = new ArrayList<>();
        /** Their document ids. */
        final List<String> foundIds = new ArrayList<>();

        Scan(ContentResolver resolver, Uri tree, Set<String> seen, Set<String> imported, Set<String> downloads) {
            this.resolver = resolver;
            this.tree = tree;
            this.seen = seen;
            this.imported = imported;
            this.downloads = downloads;
        }

        void collect(String parentId, int depth) {
            if (depth > MAX_DEPTH) return;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (cursor == null) return;
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (!downloads.contains(id)) collect(id, depth + 1);
                    } else if (!seen.contains(id) && !downloads.contains(id) && isAudio(name, mime)) {
                        long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                        long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                        if (modified > 0) {
                            // A file written a moment ago may still be growing (a download, a copy): it waits for the next check.
                            if (System.currentTimeMillis() - modified < SETTLE_MS) continue;
                        } else if (size >= 0) {
                            // The provider does not tell when the file changed: it waits until its size is the same at two checks.
                            Long before = PENDING_SIZES.put(id, size);
                            if (before == null || before != size) continue;
                        }
                        PENDING_SIZES.remove(id);
                        if (!imported.contains(storedName(name) + '/' + size)) found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                        foundIds.add(id);
                    }
                }
            }
        }
    }

    /** The name an imported copy of the file gets, as the library holds it. */
    private static String storedName(String name) {
        return name == null ? "" : FileIo.safeName(name);
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }
}
