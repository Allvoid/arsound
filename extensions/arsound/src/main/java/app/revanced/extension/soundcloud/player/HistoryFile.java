package app.revanced.extension.soundcloud.player;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/** The file that holds the listening history, one {@link ListenRecord} per line. */
final class HistoryFile {
    /**
     * Guards the file. The listen in progress is guarded by {@link ListeningStats} itself instead: the player calls the
     * hooks on its own thread and must not wait for a file to be read.
     */
    private static final Object LOCK = new Object();

    private HistoryFile() {
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), "arsound/listening.tsv");
    }

    /** Appends the record on a background thread. */
    static void append(ListenRecord record) {
        Utils.runOnBackgroundThread(() -> {
            synchronized (LOCK) {
                File file = file();
                if (file == null) return;
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(record.format() + "\n");
                } catch (Exception ex) {
                    Logger.printException(() -> "Listening stats: could not write", ex);
                }
            }
        });
    }

    /** Hands every line to the visitor, in the order they were written. Blocks while the file is read. */
    static void forEachLine(Consumer<String> visitor) {
        File file = file();
        if (file == null || !file.isFile()) return;
        synchronized (LOCK) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) visitor.accept(line);
            } catch (Exception ex) {
                Logger.printException(() -> "Listening stats: could not read", ex);
            }
        }
    }

    /**
     * Deletes the history. Not under the lock: this is called on the main thread, which must not wait for a reader. A
     * reader that has the file open finishes the old history, and a listen written just now is lost with it.
     */
    static void delete() {
        File file = file();
        //noinspection ResultOfMethodCallIgnored
        if (file != null) file.delete();
    }
}
