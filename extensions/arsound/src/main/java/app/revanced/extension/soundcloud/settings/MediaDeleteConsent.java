package app.revanced.extension.soundcloud.settings;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.List;

import app.revanced.extension.shared.Logger;

/**
 * Android's own confirmation for removing files the app cannot remove by itself (Android 11 and later). The answer
 * comes to {@link Activity#onActivityResult} under the request code given.
 */
final class MediaDeleteConsent {
    private MediaDeleteConsent() {
    }

    /** Whether there is anything to ask about and this Android can ask. */
    static boolean canAsk(List<Uri> files) {
        return !files.isEmpty() && Build.VERSION.SDK_INT >= 30;
    }

    /**
     * Opens the confirmation for the files.
     *
     * @param failure What the log says if it could not be opened.
     * @return True if the user is being asked.
     */
    static boolean ask(Activity activity, List<Uri> files, int requestCode, String failure) {
        try {
            activity.startIntentSenderForResult(MediaStore.createDeleteRequest(activity.getContentResolver(), files)
                    .getIntentSender(), requestCode, null, 0, 0, 0);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> failure, ex);
            return false;
        }
    }
}
