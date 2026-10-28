package app.revanced.extension.soundcloud.debug;

import android.content.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the parts of the log file one after another. A part that cannot be read leaves one line about it, and the
 * parts after it are still written: a half-empty file is better for finding the problem than none.
 */
public final class LogComposer {
    private final List<LogSection> sections;

    public LogComposer(List<LogSection> sections) {
        this.sections = new ArrayList<>(sections);
    }

    /** The line that opens a part, with an empty line before it. */
    public static String banner(String name) {
        return "\n==== " + name + " ====\n";
    }

    /** True if at least one part is something the app wrote down earlier. */
    public boolean hasRecords(Context context) {
        for (LogSection section : sections) {
            if (!section.isRecord()) continue;
            try {
                if (section.hasContent(context)) return true;
            } catch (Exception ignored) {
                // An unreadable record is still no record to send.
            }
        }
        return false;
    }

    /**
     * @throws java.io.IOException if the output cannot be written: the file would be cut, so the caller discards it.
     */
    public void write(Context context, OutputStream out) throws java.io.IOException {
        for (LogSection section : sections) {
            try {
                if (!section.hasContent(context)) continue;
                if (section.name() != null) out.write(banner(section.name()).getBytes(StandardCharsets.UTF_8));
                section.writeTo(context, out);
            } catch (Exception ex) {
                // If the output is what failed, this line fails too and the error reaches the caller.
                out.write(failureLine(section, ex).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static String failureLine(LogSection section, Exception ex) {
        String name = section.name() != null ? section.name() : section.getClass().getSimpleName();
        return "\n==== " + name + ": could not be written, " + ex + "\n";
    }
}
