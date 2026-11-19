package app.revanced.extension.soundcloud.settings;

import android.os.Environment;
import android.os.StatFs;

import app.revanced.extension.shared.Logger;

/** How much room the phone has left where the app keeps its files and downloads (the shared storage's volume). */
final class FreeSpace {
    private FreeSpace() {
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
