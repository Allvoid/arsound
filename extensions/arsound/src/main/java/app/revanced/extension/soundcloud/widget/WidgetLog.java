package app.revanced.extension.soundcloud.widget;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small log of its own for the player widget, kept in the app's private storage whatever the log settings are.
 * <p>
 * The playback log keeps two days and can be turned off, and the mod log is off by default; a user who sends the logs a
 * week after the widget stopped answering would send neither of them with the widget's lines in. This log holds only
 * the widget's events (a process start and why it started, a tap and what came of it, the buttons built), a few lines a
 * day, so two files of {@link #MAX_FILE_BYTES} cover weeks. Lines are written on the calling thread, because a tap may
 * be the last thing a process does before the phone ends it.
 */
public final class WidgetLog {
    static final int MAX_FILE_BYTES = 96 * 1024;
    private static final String FILE_NAME = "arsound-widget-log.txt";
    private static final String PREVIOUS_FILE_NAME = "arsound-widget-log.1.txt";

    private static volatile WidgetLog shared;

    private final File directory;
    private final SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** Public to the tests: any directory will do. */
    WidgetLog(File directory) {
        this.directory = directory;
    }

    /** The log of the app, in its private storage. */
    private static WidgetLog of(Context context) {
        WidgetLog known = shared;
        if (known != null) return known;
        synchronized (WidgetLog.class) {
            if (shared == null) shared = new WidgetLog(context.getApplicationContext() == null
                    ? context.getFilesDir() : context.getApplicationContext().getFilesDir());
            return shared;
        }
    }

    /** Appends one line, with the time and the process it came from. Never throws. */
    public static void append(Context context, String message) {
        try {
            if (context == null) return;
            of(context).write(System.currentTimeMillis(), Process.myPid(), message);
        } catch (Throwable ignored) {
            // The log must never be the reason a tap or a start fails.
        }
    }

    /** The whole log, oldest line first; empty when nothing was written. */
    public static String read(Context context) {
        try {
            return context == null ? "" : of(context).readAll();
        } catch (Throwable ex) {
            return "";
        }
    }

    synchronized void write(long now, int pid, String message) {
        try {
            String line = time.format(new Date(now)) + " [" + pid + "] " + message + "\n";
            File file = new File(directory, FILE_NAME);
            if (file.length() + line.length() > MAX_FILE_BYTES) {
                File previous = new File(directory, PREVIOUS_FILE_NAME);
                if (previous.exists() && !previous.delete()) return;
                if (!file.renameTo(previous)) return;
            }
            try (Writer output = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                output.write(line);
            }
        } catch (Exception ignored) {
            // Nothing to do: the next line tries again.
        }
    }

    synchronized String readAll() {
        StringBuilder text = new StringBuilder();
        for (String name : new String[]{PREVIOUS_FILE_NAME, FILE_NAME}) {
            File file = new File(directory, name);
            if (!file.isFile()) continue;
            try (InputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int read = 0;
                while (read < bytes.length) {
                    int count = input.read(bytes, read, bytes.length - read);
                    if (count < 0) break;
                    read += count;
                }
                text.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // A file that cannot be read is left out.
            }
        }
        return text.toString();
    }
}
