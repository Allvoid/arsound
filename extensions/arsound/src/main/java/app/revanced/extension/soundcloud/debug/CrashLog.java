package app.revanced.extension.soundcloud.debug;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Why the app closed, for "Save the logs": a user who saw a crash can only describe what was on screen, and the
 * cause is gone with the process.
 * <p>
 * Two sources. The stack of every uncaught exception is written to a small file at once, before the process dies
 * (the mod log writes on another thread and is off by default). And Android's own record of how the app's last
 * processes ended (Android 11 and later): crashes, native crashes with their traces, freezes (ANR), kills for lack
 * of memory. Android keeps that record itself, so it also covers crashes from before this version was installed.
 */
public final class CrashLog {
    private static final String FILE_NAME = "arsound-crashes.txt";
    /** The file is cut to its newer half when it grows past this. */
    private static final int MAX_FILE_BYTES = 256 * 1024;
    /** How many process endings Android is asked for. */
    private static final int EXIT_REASONS = 12;
    /** The longest part of a native crash or ANR trace put into the report. */
    private static final int MAX_TRACE_CHARS = 48 * 1024;

    private static File file;

    private CrashLog() {
    }

    /** Injection point: called when the application starts. Keeps the handler that was there before. */
    public static void install(Context context) {
        if (file != null) return;
        file = new File(context.getFilesDir(), FILE_NAME);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(thread, throwable);
            } catch (Throwable ignored) {
                // Nothing may stop the crash from reaching the handler before.
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static synchronized void write(Thread thread, Throwable throwable) throws Exception {
        String entry = "==== " + now() + ", thread " + thread.getName() + "\n" + Log.getStackTraceString(throwable) + "\n";
        if (file.length() > MAX_FILE_BYTES) keepNewerHalf();
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(entry.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private static void keepNewerHalf() throws Exception {
        byte[] tail;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = in.length() / 2;
            tail = new byte[(int) (in.length() - start)];
            in.seek(start);
            in.readFully(tail);
        }
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(tail);
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    /** The crashes written by the handler and Android's record of the last process endings; empty if none. */
    public static String report(Context context) {
        StringBuilder text = new StringBuilder();
        File crashes = new File(context.getFilesDir(), FILE_NAME);
        if (crashes.length() > 0) {
            try (InputStream in = new java.io.FileInputStream(crashes)) {
                text.append("==== crashes ====\n").append(readAll(in, Integer.MAX_VALUE)).append('\n');
            } catch (Exception ex) {
                text.append("==== crashes: unreadable, ").append(ex).append('\n');
            }
        }
        if (Build.VERSION.SDK_INT >= 30) appendExitReasons(context, text);
        return text.toString();
    }

    private static void appendExitReasons(Context context, StringBuilder text) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, EXIT_REASONS);
            if (exits.isEmpty()) return;
            text.append("==== how the app's processes ended (Android) ====\n");
            SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
            for (ApplicationExitInfo exit : exits) {
                text.append(time.format(new Date(exit.getTimestamp())))
                        .append(" ").append(exit.getProcessName())
                        .append(" reason=").append(reasonName(exit.getReason()))
                        .append(" status=").append(exit.getStatus())
                        .append(" importance=").append(exit.getImportance())
                        .append(" pss=").append(exit.getPss() / 1024).append("MB")
                        .append(" rss=").append(exit.getRss() / 1024).append("MB");
                if (exit.getDescription() != null) text.append(" description=").append(exit.getDescription());
                text.append('\n');
                int reason = exit.getReason();
                if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) {
                    try (InputStream trace = exit.getTraceInputStream()) {
                        if (trace != null) text.append(readAll(trace, MAX_TRACE_CHARS)).append('\n');
                    }
                }
            }
        } catch (Exception ex) {
            text.append("==== process endings: unreadable, ").append(ex).append('\n');
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return String.valueOf(reason);
        }
    }

    private static String readAll(InputStream in, int maxChars) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = in.read(buffer)) > 0 && bytes.size() < maxChars) bytes.write(buffer, 0, count);
        String value = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        return value.length() > maxChars ? value.substring(0, maxChars) + "\n[cut]" : value;
    }
}
