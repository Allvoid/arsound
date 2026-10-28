package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.RedactedCopy;

/**
 * One day of the playback log. It also holds the warnings and errors that SoundCloud's own code reported, among
 * them the failures of the sign-in.
 */
public final class PlaybackFileSection implements LogSection {
    /** The playback log keeps at most 20 MB a day; this leaves room for a full day. */
    private static final long MAX_BYTES = 24L * 1024 * 1024;

    private final File file;

    public PlaybackFileSection(File file) {
        this.file = file;
    }

    @Override
    public String name() {
        return file.getName();
    }

    @Override
    public boolean isRecord() {
        return true;
    }

    @Override
    public boolean hasContent(Context context) {
        return file.length() > 0;
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            RedactedCopy.copy(in, out, MAX_BYTES);
        }
    }
}
