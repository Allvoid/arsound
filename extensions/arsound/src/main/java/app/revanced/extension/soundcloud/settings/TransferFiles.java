package app.revanced.extension.soundcloud.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * The Android side of exporting and importing settings: reads the real preferences files and the file the user picked,
 * and writes the imported values. What the file looks like is {@link SettingsTransfer}; what travels is
 * {@link TransferPlan}. All of it touches the disk, so call it off the main thread.
 */
final class TransferFiles {
    private TransferFiles() {
    }

    /** The current values of every preferences file that travels, as {@code getAll()} gives them. */
    static Map<String, Map<String, ?>> read(Context context) {
        Map<String, Map<String, ?>> current = new LinkedHashMap<>();
        for (TransferPlan.Source source : TransferPlan.SOURCES) {
            current.put(source.file, context.getSharedPreferences(source.file, Context.MODE_PRIVATE).getAll());
        }
        return current;
    }

    /** The name to offer for a file made now. */
    static String suggestedName() {
        return SettingsTransfer.fileName(SettingsTransfer.dateOf(System.currentTimeMillis(), TimeZone.getDefault()));
    }

    /** Writes the settings into the file the user created with the system's picker. */
    static TransferExport export(Context context, Uri destination) throws IOException, TransferException {
        TransferExport export = SettingsTransfer.export(read(context), UpdateChecker.VERSION,
                SettingsTransfer.dateOf(System.currentTimeMillis(), TimeZone.getDefault()));
        byte[] bytes = export.json.getBytes(StandardCharsets.UTF_8);
        ContentResolver resolver = context.getContentResolver();
        try {
            if (bytes.length > SettingsTransfer.MAX_BYTES) throw new TransferException(TransferException.Reason.TOO_BIG);
            // "wt": an existing file is cut to nothing first, so no tail of an older, longer one stays behind.
            try (OutputStream out = resolver.openOutputStream(destination, "wt")) {
                if (out == null) throw new IOException("No stream for " + destination);
                out.write(bytes);
            }
        } catch (IOException | TransferException | RuntimeException ex) {
            // The picker already made an empty file; a failed export must not leave it looking like a good one.
            try {
                DocumentsContract.deleteDocument(resolver, destination);
            } catch (Exception ignored) {
                Logger.printDebug(() -> "Settings export: could not remove the empty file");
            }
            throw ex;
        }
        return export;
    }

    /** Reads and checks the file the user picked. Nothing is written to the phone. */
    static TransferBackup load(Context context, Uri source) throws IOException, TransferException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("No stream for " + source);
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) {
                // Stopped as soon as it is clear: a wrong file (a video) must not be read to its end into memory.
                if (bytes.size() + read > SettingsTransfer.MAX_BYTES) throw new TransferException(TransferException.Reason.TOO_BIG);
                bytes.write(buffer, 0, read);
            }
        }
        return SettingsTransfer.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Writes the values of the file into the preferences, file by file; each file is on the disk (commit) when this
     * returns, because the app ends its process right after. A file that fails to write stops the import with an
     * exception, the files before it stay written.
     */
    static TransferChanges apply(Context context, TransferBackup backup) throws IOException {
        TransferChanges changes = SettingsTransfer.plan(backup, read(context));
        Set<String> files = new HashSet<>(changes.writes.keySet());
        files.addAll(changes.removals.keySet());
        for (String file : files) {
            SharedPreferences.Editor editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            Set<String> removals = changes.removals.get(file);
            if (removals != null) {
                for (String key : removals) editor.remove(key);
            }
            Map<String, Object> writes = changes.writes.get(file);
            if (writes != null) {
                for (Map.Entry<String, Object> write : writes.entrySet()) put(editor, write.getKey(), write.getValue());
            }
            if (!editor.commit()) throw new IOException("Could not write the preferences " + file);
        }
        return changes;
    }

    /** One value into an editor, by its type. */
    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        // A copy: the editor keeps the set it is given, and the backup must stay as it was read.
        else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
    }
}
