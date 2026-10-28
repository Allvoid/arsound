package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.widget.Toast;

import app.revanced.extension.shared.Utils;

/** The long toast, shown the same way from every screen. */
public final class Toasts {
    private Toasts() {
    }

    /** A long toast, shown right away: call it on the main thread. */
    public static void showLong(Context context, CharSequence message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** A long toast, shown on the main thread: call it from anywhere. */
    public static void showLongOnMainThread(Context context, CharSequence message) {
        Utils.runOnMainThread(() -> showLong(context, message));
    }
}
