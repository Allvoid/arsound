package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.widget.WidgetLog;

/**
 * The widget's own log: process starts and their reasons, taps and what came of them, the buttons built. It is kept
 * for weeks whatever the log settings are, so it is in the file even when the playback log of that day is gone.
 */
public final class WidgetLogSection implements LogSection {
    /** Read once: finding out whether there is anything reads everything. */
    private String text;

    @Override
    public String name() {
        return "widget log";
    }

    @Override
    public boolean isRecord() {
        // The app writes a line at every start, so it would make "No logs yet" impossible: the other records decide.
        return false;
    }

    @Override
    public boolean hasContent(Context context) {
        return !text(context).isEmpty();
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        out.write(text(context).getBytes(StandardCharsets.UTF_8));
    }

    private String text(Context context) {
        if (text == null) text = WidgetLog.read(context);
        return text;
    }
}
