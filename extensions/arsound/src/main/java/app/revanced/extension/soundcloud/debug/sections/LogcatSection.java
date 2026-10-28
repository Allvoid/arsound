package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.ProcessCapture;

/**
 * The system log of this app: what WebView, the browser hand-off and the system itself said around a failure,
 * which the logs of the app do not have. Android shows an app only its own lines, so nothing of other apps is read.
 * <p>
 * Some phones refuse the command or let it hang, so it runs with a time limit, and every failure becomes a line
 * in the file instead of an error.
 */
public final class LogcatSection implements LogSection {
    private static final int LAST_LINES = 3000;
    private static final long TIMEOUT_MS = 3_000;
    private static final long MAX_BYTES = 1024 * 1024;

    @Override
    public String name() {
        return "logcat (this app)";
    }

    @Override
    public boolean isRecord() {
        return false;
    }

    @Override
    public boolean hasContent(Context context) {
        return true;
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws IOException {
        ProcessCapture.Result result;
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(LAST_LINES))
                    .redirectErrorStream(true)
                    .start();
            try {
                result = ProcessCapture.run(process, TIMEOUT_MS, MAX_BYTES);
            } finally {
                process.destroy();
            }
        } catch (Exception ex) {
            // The command is missing or forbidden: the file says so. Only a failing output is an error.
            write(out, "[logcat unavailable: " + ex + "]");
            return;
        }
        out.write(result.output);
        if (result.timedOut) write(out, "[logcat did not finish in " + TIMEOUT_MS / 1000 + " s, the lines above may be cut]");
        if (result.failure != null) write(out, "[logcat could not be read to the end: " + result.failure + "]");
        if (result.output.length == 0) write(out, "[logcat returned nothing: some phones do not allow it]");
    }

    private static void write(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
