package app.revanced.extension.soundcloud.download;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The hidden files a folder of the user holds while a track arrives in it. A folder lists a file at once, half
 * written and under its final name, and every music app shows it as a broken track. So the file is written under a
 * hidden name and is named only when it is whole. The hidden files are written down on the disk, so that when the
 * process is killed in the middle of a download the next start can remove what was left.
 */
final class UnfinishedParts {
    /** A file that is still arriving has a hidden name of this shape; it gets its real name once it is whole. */
    private static final String PART_PREFIX = ".arsound-";
    private static final String PART_SUFFIX = ".part";
    /** A type no provider takes an extension from, so the hidden name is kept as it is. */
    static final String MIME = "application/octet-stream";
    private static final AtomicLong partCounter = new AtomicLong();
    /** The files that are arriving now, written down on the disk so that a killed process can clean up after itself. */
    private static final String PARTS_PREFERENCES = "revanced_soundcloud_download_parts";
    private static final String PARTS = "parts";
    private static final Object PARTS_LOCK = new Object();
    /** The unfinished files of this run of the app: the sweep after a death of the process must leave them alone. */
    private static final Set<String> activeParts = ConcurrentHashMap.newKeySet();

    /** A new hidden name, not used before. */
    static String newName() {
        return PART_PREFIX + System.currentTimeMillis() + "-" + partCounter.incrementAndGet() + PART_SUFFIX;
    }

    private static boolean isPartName(String name) {
        return name.startsWith(PART_PREFIX) && name.endsWith(PART_SUFFIX);
    }

    private static SharedPreferences partsPreferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PARTS_PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Writes down a hidden file that starts to arrive, before its first byte. */
    static void note(Uri document) {
        String key = document.toString();
        synchronized (PARTS_LOCK) {
            activeParts.add(key);
            SharedPreferences preferences = partsPreferences();
            if (preferences == null) return;
            Set<String> parts = new HashSet<>(preferences.getStringSet(PARTS, Collections.emptySet()));
            parts.add(key);
            // To the disk now, not later: the process may be killed in the next second.
            preferences.edit().putStringSet(PARTS, parts).commit();
        }
    }

    /** Strikes a hidden file off: it was named, or removed. */
    static void drop(Uri document) {
        String key = document.toString();
        synchronized (PARTS_LOCK) {
            activeParts.remove(key);
            SharedPreferences preferences = partsPreferences();
            if (preferences == null) return;
            Set<String> parts = new HashSet<>(preferences.getStringSet(PARTS, Collections.emptySet()));
            if (parts.remove(key)) preferences.edit().putStringSet(PARTS, parts).apply();
        }
    }

    /**
     * Removes the hidden files that earlier runs of the app left behind when the process was killed in the middle of a
     * download. Run at the start of the app and when the folder changes. Those of downloads that run now are left alone.
     * Call off the main thread.
     */
    static void sweep(Context context) {
        SharedPreferences preferences = partsPreferences();
        if (preferences == null) return;
        List<String> left = new ArrayList<>();
        synchronized (PARTS_LOCK) {
            for (String key : preferences.getStringSet(PARTS, Collections.emptySet())) {
                if (!activeParts.contains(key)) left.add(key);
            }
        }
        if (left.isEmpty()) return;

        List<String> done = new ArrayList<>();
        for (String key : left) {
            if (removeUnfinished(context, Uri.parse(key))) done.add(key);
        }
        if (done.isEmpty()) return;
        synchronized (PARTS_LOCK) {
            Set<String> parts = new HashSet<>(preferences.getStringSet(PARTS, Collections.emptySet()));
            if (parts.removeAll(done)) preferences.edit().putStringSet(PARTS, parts).apply();
        }
    }

    /** @return True if the file is dealt with and can leave the list; false if it has to be tried again later. */
    private static boolean removeUnfinished(Context context, Uri document) {
        ContentResolver resolver = context.getContentResolver();
        boolean seen = false;
        String name = null;
        try (Cursor cursor = resolver.query(document, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                seen = true;
                name = cursor.getString(0);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not look for the unfinished " + document + ": " + ex);
            return false;
        }
        if (!seen) {
            // No such file: already removed, or its card is out. Only the first lets it go off the list.
            try {
                Uri tree = DocumentsContract.buildTreeDocumentUri(document.getAuthority(),
                        DocumentsContract.getTreeDocumentId(document));
                return new TreeFolder(tree).canWrite(context);
            } catch (Exception ex) {
                return true;
            }
        }
        // It carries a name of its own: the download was finished after all, this is a track and not a leftover.
        if (name == null || !isPartName(name)) return true;
        String leftover = name;
        try {
            DocumentsContract.deleteDocument(resolver, document);
            Logger.printInfo(() -> "Removed the unfinished " + leftover + " of a download that was cut off");
            return true;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not remove the unfinished " + document + ": " + ex);
            return false;
        }
    }
}
