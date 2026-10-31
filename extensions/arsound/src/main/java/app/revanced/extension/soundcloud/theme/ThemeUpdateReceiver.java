package app.revanced.extension.soundcloud.theme;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Wakes the app right after it was updated, so the resource table of the chosen theme is built then, with nothing on
 * screen, and not in the first start the user makes: an update makes the kept table out of date, and building it again
 * takes a long moment on a slow phone. The process starting for this broadcast already builds it in
 * {@link ArsoundTheme#onApplicationCreate}; the receiver makes sure of it off the main thread. Added to the manifest by
 * the theme patch.
 */
public final class ThemeUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            try {
                ThemeLifecycle.prepareChosen(application != null ? application : context);
            } catch (Throwable ex) {
                Logger.printException(() -> "Theme: could not prepare the theme after the update", ex);
            } finally {
                pending.finish();
            }
        });
    }
}
