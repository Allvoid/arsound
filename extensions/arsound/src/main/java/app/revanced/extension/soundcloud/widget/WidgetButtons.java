package app.revanced.extension.soundcloud.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The way the buttons of the player widget (previous, next, pause) reach the app. SoundCloud builds each button with
 * {@code PendingIntent.getBroadcast}; the patch makes those three calls go through {@link #pending}, which gives the
 * same {@code PendingIntent} unless the phone is one where the launcher's broadcast does not arrive (see
 * {@link ButtonPath}): then it opens {@link WidgetRelayActivity}, which hands the very same broadcast to the app from
 * inside it. Everything after that is SoundCloud's own, and the log's "tap arrived" line says which way the tap came.
 * <p>
 * The way is chosen in Arsound's settings ("Widget buttons"): automatic, through the app, or SoundCloud's own.
 */
public final class WidgetButtons {
    /** The setting of the way, see {@link ButtonPath}. */
    public static final String MODE_KEY = "widget_buttons_mode";
    /** Set once a tap arrived by SoundCloud's own broadcast: that way works on this phone, and automatic keeps it. */
    private static final String NATIVE_WORKED_KEY = "widget_native_tap_worked";
    /** The broadcast the relay sends carries this, so the receiver can tell it from a tap the launcher sent. */
    static final String RELAYED_EXTRA = "arsound_relayed";
    static final String ORIGINAL_EXTRA = "arsound_original";
    /** A request code apart from SoundCloud's own, so the two kinds of intents never replace each other. */
    private static final int CODE_OFFSET = 1000;

    private WidgetButtons() {
    }

    /**
     * Injection point. Replaces {@code PendingIntent.getBroadcast(context, code, intent, flags)} for the buttons.
     */
    public static PendingIntent pending(Context context, int code, Intent intent, int flags) {
        try {
            if (throughApp(context)) {
                Intent relay = new Intent(context, WidgetRelayActivity.class)
                        .setAction("arsound.widget.relay." + code)
                        .putExtra(ORIGINAL_EXTRA, intent)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY);
                return PendingIntent.getActivity(context, CODE_OFFSET + code, relay, flags);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: the relay button was not built, SoundCloud's own is used", ex);
        }
        return PendingIntent.getBroadcast(context, code, intent, flags);
    }

    /** Whether the buttons go through the app now. */
    static boolean throughApp(Context context) {
        return ButtonPath.throughApp(mode(), isXiaomi(), nativeWorked(context));
    }

    /** What the log says about the way the buttons are built. */
    static String describe(Context context) {
        return ButtonPath.describe(mode(), isXiaomi(), nativeWorked(context));
    }

    private static long mode() {
        return Settings.getLong(MODE_KEY, ButtonPath.AUTO);
    }

    private static boolean nativeWorked(Context context) {
        return Settings.getBoolean(NATIVE_WORKED_KEY, false);
    }

    /** Called when a tap arrived by SoundCloud's own broadcast: from then on this phone is known to deliver it. */
    static void noteNativeTap(Context context) {
        if (Settings.getBoolean(NATIVE_WORKED_KEY, false)) return;
        Settings.putBoolean(NATIVE_WORKED_KEY, true);
        WidgetLog.append(context, "a tap arrived by SoundCloud's own broadcast: automatic keeps that way on this phone");
    }

    /** Xiaomi's firmware: HyperOS or MIUI, by the properties they set. */
    static boolean isXiaomi() {
        return !systemProperty("ro.mi.os.version.name").isEmpty() || !systemProperty("ro.miui.ui.version.name").isEmpty()
                || "xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private static String systemProperty(String name) {
        try {
            Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = get.invoke(null, name, "");
            return value == null ? "" : value.toString();
        } catch (Throwable ex) {
            return "";
        }
    }
}
