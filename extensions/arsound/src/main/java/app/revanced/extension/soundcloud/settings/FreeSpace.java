package app.revanced.extension.soundcloud.settings;

import android.os.Environment;
import android.os.StatFs;

import app.revanced.extension.shared.Logger;

/**
 * How much room the phone's internal storage has left: the volume of the app's own files and of the shared storage's
 * default folders. A download folder picked on an SD card is another volume that this does not measure, so the screen
 * names what it shows ({@link #label}).
 */
final class FreeSpace {
    private FreeSpace() {
    }

    /** The name of the line that shows {@link #bytes}: it is the internal storage, not a card the downloads may go to. */
    static String label(boolean russian) {
        return russian ? "Свободно во внутренней памяти" : "Free in the internal storage";
    }

    /** The bytes the app may still use, or -1 when the system does not say. Reads the disk's state: off the main thread. */
    static long bytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Exception ex) {
            Logger.printException(() -> "Storage: could not read the free space", ex);
            return -1;
        }
    }
}
