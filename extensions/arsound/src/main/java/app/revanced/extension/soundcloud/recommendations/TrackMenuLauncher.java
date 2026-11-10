package app.revanced.extension.soundcloud.recommendations;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;

/**
 * Opens SoundCloud's own track menu (the bottom sheet of the "⋮" of a track in any list of the app: add to a playlist,
 * play next, share and the rest) for a track of the "My Wave" list. The app opens it by starting its main activity with
 * the action {@code SHOW_TRACK_MENU} and the menu's parameters ({@code IntentNavigation.A0}); this does the same, by
 * reflection, with the names of the shrunk build the patches target. Main thread.
 */
final class TrackMenuLauncher {
    private static final String PARAMS = "com.soundcloud.android.features.bottomsheet.track.TrackBottomSheetFragment$Params";
    private static final String CONTEXT = "com.soundcloud.android.foundation.attribution.EventContextMetadata";
    private static final String CAPTION = "com.soundcloud.android.repostaction.CaptionParams";
    private static final String NAVIGATION = "com.soundcloud.android.navigation.IntentNavigation";

    private TrackMenuLauncher() {
    }

    /**
     * @param urn The track, as {@code "soundcloud:tracks:123"}.
     * @return False when the menu could not be opened (the names of the app differ).
     */
    static boolean open(Context context, String urn) {
        try {
            Activity activity = activityOf(context);
            if (activity == null) return false;
            ClassLoader loader = activity.getClassLoader();
            Class<?> paramsClass = Class.forName(PARAMS, false, loader);
            Class<?> contextClass = Class.forName(CONTEXT, false, loader);
            Class<?> captionClass = Class.forName(CAPTION, false, loader);
            Object metadata = contextClass.getMethod("fromPage", String.class).invoke(null, "my_wave");
            Constructor<?> constructor = paramsClass.getConstructor(String.class, String.class, contextClass, int.class,
                    captionClass, boolean.class, String.class, boolean.class, boolean.class);
            Object params = constructor.newInstance(urn, null, metadata, 0, null, false, "my_wave", false, false);
            Method intent = Class.forName(NAVIGATION, false, loader).getMethod("A0", Class.class, Context.class, paramsClass);
            activity.startActivity((Intent) intent.invoke(null, activity.getClass(), activity, params));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "My Wave: the track menu did not open for " + urn, ex);
            return false;
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
