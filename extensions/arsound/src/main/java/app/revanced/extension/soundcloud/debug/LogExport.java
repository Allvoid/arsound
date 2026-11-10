package app.revanced.extension.soundcloud.debug;

import android.app.Activity;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.shared.debug.PlaybackLog;
import app.revanced.extension.soundcloud.debug.sections.CrashSection;
import app.revanced.extension.soundcloud.debug.sections.HeaderSection;
import app.revanced.extension.soundcloud.debug.sections.LogcatSection;
import app.revanced.extension.soundcloud.debug.sections.ModLogSection;
import app.revanced.extension.soundcloud.debug.sections.PlaybackFileSection;
import app.revanced.extension.soundcloud.debug.sections.SignInEnvironmentSection;
import app.revanced.extension.soundcloud.debug.sections.WidgetLogSection;
import app.revanced.extension.soundcloud.debug.sections.WidgetStateSection;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * "Save the logs": puts the header, the sign-in environment, the widget state, the crashes, the widget log, the
 * playback log of the last two days, the mod log and the system log of the app into one file in Downloads, then shows
 * where it is and offers to send it.
 * <p>
 * Used by the settings screen and by the sign-in page, where the settings cannot be reached yet.
 */
public final class LogExport {
    /** A second tap while a file is being made would make a second file. */
    private static final AtomicBoolean saving = new AtomicBoolean();

    private LogExport() {
    }

    /**
     * Saves the logs in the background and shows the result.
     *
     * @param requireRecords True on the settings screen: when the app has written nothing down yet, it says
     *                       "No logs yet" instead of saving a file with the phone's state alone. The sign-in page
     *                       passes false: before the sign-in the state of the phone is what there is to send.
     */
    public static void save(Activity activity, boolean requireRecords) {
        if (!saving.compareAndSet(false, true)) {
            // The first tap is still collecting (logcat can take a few seconds): say so instead of staying silent.
            Toasts.showLongOnMainThread(activity, Lang.text("Журналы уже сохраняются…", "The logs are already being saved…"));
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                saveNow(activity, requireRecords);
            } finally {
                saving.set(false);
            }
        });
    }

    /** The parts of the file, in the order they are written. */
    private static List<LogSection> sections() {
        List<LogSection> sections = new ArrayList<>();
        sections.add(new HeaderSection());
        sections.add(new SignInEnvironmentSection());
        sections.add(new WidgetStateSection());
        // Crashes first among the records: they are what a user who saw the app close sends the file for.
        sections.add(new CrashSection());
        sections.add(new WidgetLogSection());
        for (File day : PlaybackLog.files()) sections.add(new PlaybackFileSection(day));
        sections.add(new ModLogSection());
        sections.add(new LogcatSection());
        return sections;
    }

    private static void saveNow(Activity activity, boolean requireRecords) {
        LogComposer composer = new LogComposer(sections());
        if (requireRecords && !composer.hasRecords(activity)) {
            Utils.runOnMainThread(() -> SavedLogsDialog.showNoLogs(activity));
            return;
        }

        String name = "arsound-logs-"
                + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date()) + ".txt";
        LogDestination destination = LogDestination.create(activity, name);
        try {
            try (OutputStream out = destination.open()) {
                composer.write(activity, out);
            }
            android.net.Uri saved = destination.finish();
            destination.removeEarlier();
            Utils.runOnMainThread(() -> showSaved(activity, destination.shownPath(), saved));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the logs", ex);
            destination.discard();
            Toasts.showLongOnMainThread(activity,
                    Lang.text("Не удалось сохранить журналы", "Could not save the logs"));
        }
    }

    private static void showSaved(Activity activity, String path, android.net.Uri uri) {
        // The user can leave the screen while the file is made, and a dialog needs a screen.
        if (activity.isFinishing() || activity.isDestroyed()) {
            Toasts.showLong(activity.getApplicationContext(), Lang.text("Журналы сохранены: ", "The logs are saved: ") + path);
            return;
        }
        SavedLogsDialog.show(activity, path, uri);
    }
}
