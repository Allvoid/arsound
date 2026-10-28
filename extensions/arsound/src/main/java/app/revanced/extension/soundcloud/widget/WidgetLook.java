package app.revanced.extension.soundcloud.widget;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;

import app.revanced.extension.shared.Logger;

/** The player widget on the home screen and the day or night look of the phone it is drawn in. */
final class WidgetLook {
    /** SoundCloud's provider of the player widget: it has the widget built again, from the track it has. */
    private static final String PROVIDER = "com.soundcloud.android.playback.widget.service.PlayerAppWidgetProvider";

    /** The look of the phone (night or not) as it was last seen, to tell a flip from another change of configuration. */
    private static volatile boolean lastNight;

    private WidgetLook() {
    }

    /**
     * Has the widget on the home screen built again, so a change of the setting shows at once and not with the next
     * track. It is the update the system sends when the widget is added; with no widget on the screen nothing happens.
     */
    static void refresh(Context context) {
        try {
            ComponentName provider = new ComponentName(context, PROVIDER);
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(provider);
            if (ids == null || ids.length == 0) return;
            context.sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not ask for a new widget", ex);
        }
    }

    /**
     * Has the widget built again when the phone flips between its day and night look. The launcher draws the card in
     * the new look by itself, but keeps the colour filter on the heart, which was fitted to the old card until the
     * next track, like or pause. Called once, when the app starts.
     */
    static void watch(Application application) {
        try {
            lastNight = isNight();
            application.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    try {
                        // The system's configuration is already the new one when this runs.
                        boolean night = isNight();
                        if (night == lastNight) return;
                        lastNight = night;
                        if (WidgetHeart.isEnabled()) refresh(application);
                    } catch (Throwable ex) {
                        Logger.printException(() -> "Widget heart: could not follow the phone's look", ex);
                    }
                }

                @Override
                public void onLowMemory() {
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not watch the phone's look", ex);
        }
    }

    /**
     * True when the phone is in its night look, which the launcher draws the card in. Asked of the system, not of
     * the app: the app can keep its own screens dark whatever the phone does.
     */
    static boolean isNight() {
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
