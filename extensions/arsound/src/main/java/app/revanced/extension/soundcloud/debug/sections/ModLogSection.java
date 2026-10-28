package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.shared.debug.LogFile;
import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.SecretRedactor;

/** The log of the mod itself, if "Log to a file" was on. */
public final class ModLogSection implements LogSection {
    /** Read once: finding out whether there is anything reads everything. */
    private String log;

    @Override
    public String name() {
        return "arsound-log";
    }

    @Override
    public boolean isRecord() {
        return true;
    }

    @Override
    public boolean hasContent(Context context) {
        return !log().isEmpty();
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        out.write(SecretRedactor.redact(log()).getBytes(StandardCharsets.UTF_8));
    }

    private String log() {
        if (log == null) log = LogFile.read();
        return log;
    }
}
