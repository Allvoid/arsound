package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.revanced.extension.shared.Logger;

/**
 * A part of the recognition the phone refused (the neural processor, the native library), not tried again until the
 * app is installed or updated: trying again meant compiling for minutes, or downloading 38 MB, every time. A small
 * file in the recognition's folder holds when the app was installed and why.
 */
final class UntilUpdate {
    /** When the app was installed or last updated; it does not change while the process lives. */
    private static volatile long installed = -1;

    private final String name;
    /** What the file said, read once per process: it is asked for on the main thread, before an offer is shown. */
    private volatile Boolean set;

    /** @param name The file of the mark: include the version of what was refused, so a new version is tried. */
    UntilUpdate(String name) {
        this.name = name;
    }

    /** When the app was installed or last updated; read from the system once per process. */
    static long installed(Context context) {
        long known = installed;
        if (known >= 0) return known;
        try {
            known = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ex) {
            known = 0;
        }
        installed = known;
        return known;
    }

    private File file(Context context) {
        return new File(AsrInstaller.root(context), name);
    }

    /** Whether the mark holds for this install of the app; a mark of an earlier install is removed. */
    boolean isSet(Context context) {
        Boolean known = set;
        if (known != null) return known;
        boolean now = read(context);
        set = now;
        return now;
    }

    private boolean read(Context context) {
        File file = file(context);
        if (!file.isFile()) return false;
        try {
            String[] words = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            if (words.length > 0 && Long.parseLong(words[0]) == installed(context)) return true;
        } catch (IOException | NumberFormatException ex) {
            // A damaged mark is no mark.
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return false;
    }

    /** Sets the mark for this install of the app. */
    void set(Context context, String why) {
        set = true;
        File file = file(context);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((installed(context) + " " + why.replace('\n', ' ')).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printInfo(() -> "Transcribe: could not keep the mark " + name, ex);
        }
    }
}
