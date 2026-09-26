package app.revanced.extension.soundcloud.local;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

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
    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma"};

    private static volatile long lastCheck;
    private static volatile boolean checking;

    private WatchFolder() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
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
        if (folder == null || checking) return;
        checking = true;
        lastCheck = System.currentTimeMillis();
        Utils.runOnBackgroundThread(() -> {
            try {
                SharedPreferences preferences = preferences();
                Set<String> seen = new HashSet<>(preferences.getStringSet(SEEN, new HashSet<>()));
                List<Uri> found = new ArrayList<>();
                List<String> foundIds = new ArrayList<>();
                // Files imported by hand earlier have the same name and size: they are not copied twice.
                Set<String> imported = new HashSet<>();
                for (File file : LocalMusic.getFiles(context)) imported.add(file.getName() + '/' + file.length());
                collect(context.getContentResolver(), folder, DocumentsContract.getTreeDocumentId(folder), seen, imported, found, foundIds, 0);

                List<File> files = found.isEmpty() ? new ArrayList<>() : LocalMusic.importFileList(context, found);
                seen.addAll(foundIds);
                preferences.edit().putStringSet(SEEN, seen).apply();
                Logger.printInfo(() -> "Watch folder: " + found.size() + " new, imported " + files.size());
                if (files.isEmpty() && !report) return;

                LocalMusic.onFileAdded();
                String saved = SavedPlaylist.getUrn();
                Utils.runOnMainThread(() -> {
                    if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
                    Toast.makeText(context, files.isEmpty()
                            ? text("В папке нет новых треков", "No new tracks in the folder")
                            : text("Импортировано из папки: ", "Imported from the folder: ") + files.size(), Toast.LENGTH_SHORT).show();
                });
            } catch (SecurityException ex) {
                // The folder was removed or its access was taken back.
                Logger.printInfo(() -> "Watch folder is not readable: " + ex);
                if (report) Utils.runOnMainThread(() -> Toast.makeText(context,
                        text("Нет доступа к папке — выберите её заново", "No access to the folder, pick it again"), Toast.LENGTH_LONG).show());
            } catch (Exception ex) {
                Logger.printException(() -> "Watch folder check failure", ex);
            } finally {
                checking = false;
            }
        });
    }

    private static void collect(ContentResolver resolver, Uri tree, String parentId, Set<String> seen,
                                Set<String> imported, List<Uri> found, List<String> foundIds, int depth) {
        if (depth > 4) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collect(resolver, tree, id, seen, imported, found, foundIds, depth + 1);
                } else if (!seen.contains(id) && isAudio(name, mime)) {
                    if (!imported.contains(name + '/' + cursor.getLong(3))) found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    foundIds.add(id);
                }
            }
        }
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }
}
