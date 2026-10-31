package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.system.ErrnoException;
import android.system.OsConstants;

/**
 * Tells a full storage apart from other failures of writing: a download or a copy that runs out of space is not
 * helped by trying again, and the user must hear "free some space", not "check the connection".
 */
final class DiskErrors {
    private DiskErrors() {
    }

    /** Whether the error, or one of its causes, is the storage being full (ENOSPC). */
    static boolean isNoSpace(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AsrInstaller.NoSpace) return true;
            if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.ENOSPC) return true;
            String message = cause.getMessage();
            if (message != null && (message.contains("ENOSPC") || message.contains("No space left"))) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
