package app.revanced.extension.shared.debug;

import android.content.Context;

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
 * A playback log kept for two days, to find out afterwards what went wrong with playback:
 * a user who hit a bug saves it with one button and sends the file.
 * <p>
 * One file per day in {@code Android/data/<package>/files/playback-log/}, so it can be pulled with adb
 * without root. Files older than {@link #KEEP_MS} are deleted when a new day starts.
 */
public final class PlaybackLog {
    private static final String DIRECTORY = "playback-log";
    private static final long KEEP_MS = 2L * 24 * 60 * 60 * 1000;
    /** A safety limit for one day, in case something logs in a loop. */
    private static final long MAX_DAY_BYTES = 20_000_000;

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "arsound-playback-log");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Only touched on the writer thread. */
    private static String currentDay;

    private PlaybackLog() {
    }

    /** Off only if the user turned the playback log off: then nothing is written. */
    public static volatile boolean enabled;

    /**
     * The kept files, oldest day first. Waits a moment for lines that are still being written,
     * so a saved copy includes what happened right before.
     */
    public static File[] files() {
        try {
            writer.submit(() -> {
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        File directory = getDirectory();
        File[] files = directory == null ? null : directory.listFiles(
                (dir, name) -> name.startsWith("playback-") && name.endsWith(".txt"));
        if (files == null) return new File[0];
        // The names hold the date as yyyy-MM-dd, so sorting by name sorts by day.
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    /** Appends one line. Never throws and never blocks the calling thread. */
    public static void append(String tag, String message) {
        if (!enabled) return;
        try {
            long now = System.currentTimeMillis();
            writer.execute(() -> write(now, tag, message));
        } catch (Exception ignored) {
        }
    }

    private static void write(long now, String tag, String message) {
        try {
            File directory = getDirectory();
            if (directory == null) return;

            Date date = new Date(now);
            String day = DAY.format(date);
            if (!day.equals(currentDay)) {
                currentDay = day;
                deleteOld(directory, now);
            }

            File file = new File(directory, "playback-" + day + ".txt");
            if (file.length() > MAX_DAY_BYTES) return;

            String line = TIME.format(date) + " " + tag + ": " + message + "\n";
            try (Writer output = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                output.write(line);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteOld(File directory, long now) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (now - file.lastModified() > KEEP_MS) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static File getDirectory() {
        Context context = Utils.getContext();
        if (context == null) return null;

        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File directory = new File(base, DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return directory;
    }
}
