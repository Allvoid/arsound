package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;
import android.os.Build;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/** What the log came from: without the versions a log of a fixed bug looks like a new one. */
public final class HeaderSection implements LogSection {
    @Override
    public String name() {
        return null;
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
    public void writeTo(Context context, OutputStream out) throws Exception {
        String soundCloud = "?";
        try {
            soundCloud = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String header = "Arsound " + UpdateChecker.VERSION + ", SoundCloud " + soundCloud + ", " + context.getPackageName() + "\n"
                + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Saved " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()) + "\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
    }
}
