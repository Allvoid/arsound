package app.revanced.extension.shared.debug;

import android.content.Context;

import java.io.BufferedWriter;
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
import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.shared.Utils;

/**
 * A playback log kept for two days, to find out afterwards what went wrong with playback:
 * a user who hit a bug saves it with one button and sends the file.
 * <p>
 * One file per day in {@code Android/data/<package>/files/playback-log/}, so it can be pulled with adb
 * without root. Files older than {@link #KEEP_MS} are deleted when a new day starts.
 * <p>
 * The log is on for everyone and the player writes to it all the time, so a line must cost little: the file of the
 * day stays open, and what was written goes to the disk once no more lines wait (a burst of lines is one write,
 * a single line is on the disk at once).
 */
public final class PlaybackLog {
    private static final String DIRECTORY = "playback-log";
    private static final long KEEP_MS = 2L * 24 * 60 * 60 * 1000;
    /** A safety limit for one day, in case something logs in a loop. */
    private static final long MAX_DAY_BYTES = 20_000_000;
    /** The most lines that wait to be written. Something that logs faster than the disk takes them loses the rest. */
    private static final int MAX_WAITING = 4_000;
    private static final String OWN_TAG = "playback-log";

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "arsound-playback-log");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Lines handed to the writer and not written yet. */
    private static final AtomicInteger waiting = new AtomicInteger();
    /** Lines left out since the last one written, because too many waited. */
    private static final AtomicInteger dropped = new AtomicInteger();

    // The rest is only touched on the writer thread.
    /** The day the open file is of, or the day found full. */
    private static String currentDay;
    private static File currentFile;
    /** The file of the day, or null: not opened yet, closed after a failure, or the day is full. */
    private static Writer output;
    /** About how much the file of the day holds (counted in characters, which is close enough for a safety limit). */
    private static long dayBytes;
    /** The day reached {@link #MAX_DAY_BYTES}: nothing more is written until the next one. */
    private static boolean dayFull;

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
            writer.submit(PlaybackLog::flush).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        File directory = getDirectory();
        // The files past their two days are deleted only once a line of a new day is written; until then they are
        // left out here, so they do not get into the copy the user sends.
        long now = System.currentTimeMillis();
        File[] files = directory == null ? null : directory.listFiles((dir, name) ->
                name.startsWith("playback-") && name.endsWith(".txt") && now - new File(dir, name).lastModified() <= KEEP_MS);
        if (files == null) return new File[0];
        // The names hold the date as yyyy-MM-dd, so sorting by name sorts by day.
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    /** Appends one line. Never throws and never blocks the calling thread. */
    public static void append(String tag, String message) {
        if (!enabled) return;
        if (waiting.get() >= MAX_WAITING) {
            dropped.incrementAndGet();
            return;
        }
        waiting.incrementAndGet();
        try {
            long now = System.currentTimeMillis();
            writer.execute(() -> write(now, tag, message));
        } catch (Exception ignored) {
            waiting.decrementAndGet();
        }
    }

    private static void write(long now, String tag, String message) {
        boolean lastWaiting = waiting.decrementAndGet() <= 0;
        try {
            Date date = new Date(now);
            String day = DAY.format(date);
            if (!day.equals(currentDay) || (output == null && !dayFull)) {
                if (!open(day, now)) return;
            }
            if (dayFull) return;

            String time = TIME.format(date);
            int lost = dropped.getAndSet(0);
            if (lost > 0) put(time + " " + OWN_TAG + ": " + lost + " lines left out, they came faster than they are written\n");
            put(time + " " + tag + ": " + message + "\n");
            if (dayBytes > MAX_DAY_BYTES) {
                // Said in the file: a log that just stops reads as if nothing more had happened.
                put(time + " " + OWN_TAG + ": the limit of a day is reached, nothing more is written until tomorrow\n");
                dayFull = true;
                close();
                return;
            }
            if (lastWaiting) {
                output.flush();
                // The folder may have been cleared while the file was open: the lines would go nowhere until tomorrow.
                if (!currentFile.isFile()) close();
            }
        } catch (Exception ex) {
            // Opened again by the next line.
            close();
        }
    }

    /** Opens the file of the day to add to it. False when there is no folder to write to yet. */
    private static boolean open(String day, long now) throws Exception {
        close();
        File directory = getDirectory();
        if (directory == null) return false;
        if (!day.equals(currentDay)) deleteOld(directory, now);
        currentDay = day;
        currentFile = new File(directory, "playback-" + day + ".txt");
        dayBytes = currentFile.length();
        dayFull = dayBytes > MAX_DAY_BYTES;
        if (!dayFull) {
            output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(currentFile, true), StandardCharsets.UTF_8));
        }
        return true;
    }

    private static void put(String line) throws Exception {
        output.write(line);
        dayBytes += line.length();
    }

    private static void flush() {
        try {
            if (output != null) output.flush();
        } catch (Exception ex) {
            close();
        }
    }

    private static void close() {
        Writer open = output;
        output = null;
        if (open == null) return;
        try {
            open.close();
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
        // Not Utils.getContext(): before the context is set it logs an error, the error comes here as a line, and the
        // line asks for the context again.
        Context context = Utils.peekContext();
        if (context == null) return null;

        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File directory = new File(base, DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        return directory;
    }
}
