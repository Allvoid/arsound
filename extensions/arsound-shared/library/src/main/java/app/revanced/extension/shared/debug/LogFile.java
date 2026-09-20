package app.revanced.extension.shared.debug;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.shared.Utils;

/**
 * Writes the log to a file inside the app storage, so a problem that happens once in a few days
 * can be looked at afterwards. Logcat is not enough for that: it is cleared on reboot and is not
 * reachable without a computer.
 * <p>
 * The file is written only while the setting is on. Two files of {@link #MAX_FILE_BYTES} are kept,
 * so the log never grows past about two megabytes.
 */
public final class LogFile {
    /** The preferences of the SoundCloud patches. Read directly to keep this class free of their settings. */
    private static final String PREFERENCES_NAME = "revanced_soundcloud";
    private static final String ENABLED_KEY = "file_logging";

    private static final String FILE_NAME = "arsound-log.txt";
    private static final String PREVIOUS_FILE_NAME = "arsound-log.1.txt";
    private static final int MAX_FILE_BYTES = 1_000_000;

    /** A single thread keeps the lines in order and off the thread that logged them. */
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "arsound-log");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static Boolean enabled;

    /**
     * Guards against logging about logging: reading the context logs an error while the app is still
     * starting, and that error would come back here, and so on until the stack runs out.
     */
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    private LogFile() {
    }

    public static boolean isEnabled() {
        Boolean cached = enabled;
        if (cached != null) return cached;

        SharedPreferences preferences = getPreferences();
        boolean value = preferences != null && preferences.getBoolean(ENABLED_KEY, false);
        // Without a context the value cannot be read yet, so it is not cached either.
        if (preferences != null) enabled = value;
        return value;
    }

    public static void setEnabled(boolean value) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(ENABLED_KEY, value).apply();
        enabled = value;
    }

    /**
     * Appends one line. Does nothing while the setting is off, and never throws:
     * logging must not be able to break the app it logs.
     */
    public static void append(String level, String tag, String message) {
        if (Boolean.TRUE.equals(inside.get())) return;

        inside.set(Boolean.TRUE);
        try {
            appendInternal(level, tag, message);
        } finally {
            inside.set(Boolean.FALSE);
        }
    }

    private static void appendInternal(String level, String tag, String message) {
        if (!isEnabled()) return;

        try {
            String line = TIME.format(new Date()) + " " + level + " " + tag + ": " + message + "\n";
            writer.execute(() -> write(line));
        } catch (Exception ignored) {
        }
    }

    /** @return The whole log, oldest lines first, or an empty string if nothing was written. */
    public static String read() {
        StringBuilder text = new StringBuilder();
        for (String name : new String[]{PREVIOUS_FILE_NAME, FILE_NAME}) {
            File file = getFile(name);
            if (file == null || !file.exists()) continue;

            try {
                byte[] bytes = new byte[(int) file.length()];
                try (java.io.InputStream input = new java.io.FileInputStream(file)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int count = input.read(bytes, read, bytes.length - read);
                        if (count < 0) break;
                        read += count;
                    }
                    text.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
        return text.toString();
    }

    public static void clear() {
        for (String name : new String[]{PREVIOUS_FILE_NAME, FILE_NAME}) {
            File file = getFile(name);
            if (file != null && file.exists() && !file.delete()) {
                // Nothing to do: the next write simply appends to the file that is still there.
                return;
            }
        }
    }

    /** @return The size of the stored log in bytes. */
    public static long size() {
        long size = 0;
        for (String name : new String[]{PREVIOUS_FILE_NAME, FILE_NAME}) {
            File file = getFile(name);
            if (file != null && file.exists()) size += file.length();
        }
        return size;
    }

    private static void write(String line) {
        try {
            File file = getFile(FILE_NAME);
            if (file == null) return;

            if (file.length() + line.length() > MAX_FILE_BYTES) {
                File previous = getFile(PREVIOUS_FILE_NAME);
                if (previous != null) {
                    if (previous.exists() && !previous.delete()) return;
                    if (!file.renameTo(previous)) return;
                }
            }

            try (Writer output = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                output.write(line);
            }
        } catch (Exception ignored) {
        }
    }

    private static File getFile(String name) {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), name);
    }

    private static SharedPreferences getPreferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
