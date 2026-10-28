package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.soundcloud.debug.CrashLog;
import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.SecretRedactor;

/**
 * The crashes and how the processes of the app ended. They come first among the records: they are what a user who
 * saw the app close sends the file for.
 */
public final class CrashSection implements LogSection {
    /** Read once: finding out whether there is anything reads everything. */
    private String report;

    @Override
    public String name() {
        // The report has its own headings.
        return null;
    }

    @Override
    public boolean isRecord() {
        return true;
    }

    @Override
    public boolean hasContent(Context context) {
        return !report(context).isEmpty();
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        out.write(("\n" + SecretRedactor.redact(report(context))).getBytes(StandardCharsets.UTF_8));
    }

    private String report(Context context) {
        if (report == null) report = CrashLog.report(context);
        return report;
    }
}
