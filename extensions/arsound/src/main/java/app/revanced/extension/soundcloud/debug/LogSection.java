package app.revanced.extension.soundcloud.debug;

import android.content.Context;

import java.io.OutputStream;

/**
 * One part of the file made by "Save the logs": the header, the sign-in environment, the crashes, one day of
 * the playback log and so on. {@link LogComposer} writes the parts one after another.
 */
public interface LogSection {
    /**
     * The heading line of the part, or null when the part prints its own headings (the header, the crashes).
     * Also names the part in the line that replaces it when it cannot be read.
     */
    String name();

    /**
     * True for what the app wrote down earlier (crashes, the playback log, the mod log); false for what is read
     * from the phone at this moment (the header, the environment, logcat). A file without a single earlier
     * record is "no logs yet" on the settings screen.
     */
    boolean isRecord();

    /** False when there is nothing to write: the part is left out of the file. */
    boolean hasContent(Context context) throws Exception;

    void writeTo(Context context, OutputStream out) throws Exception;
}
