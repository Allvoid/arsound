package app.revanced.extension.soundcloud.widget;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

import app.revanced.extension.shared.Logger;

/**
 * Lines in the log about the player widget on the home screen, for the log the user sends when a button of the widget
 * does nothing: whether the widget was built with its buttons, whether a tap came to the app at all, and what the phone
 * allows the app in the background (on Xiaomi firmware the autostart and the "pop-up windows in the background"
 * permissions decide whether a tap on the widget reaches the app). A tap with no "tap arrived" line after it was
 * stopped before the app, by the launcher or the system.
 */
@SuppressWarnings("unused")
public final class WidgetDiagnostics {
    /** Xiaomi's own app operations: autostart, and starting a screen from the background. */
    private static final int XIAOMI_OP_AUTOSTART = 10008;
    private static final int XIAOMI_OP_BACKGROUND_START = 10021;

    private static volatile boolean environmentLogged;

    private WidgetDiagnostics() {
    }

    /** Injection point. The start of {@code PlayerWidgetRemoteViews.a(Context, boolean)}: the buttons are wired for the state. */
    public static void onButtonsWired(Context context, boolean playing) {
        try {
            logEnvironment(context);
            Logger.printInfo(() -> "Widget: buttons wired, playing " + playing + ", the play button "
                    + (playing ? "sends a broadcast (pause)" : "opens the app (play)") + ", previous and next send broadcasts");
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the buttons", ex);
        }
    }

    /** Injection point. The start of {@code HomescreenWidgetBroadcastReceiver.onReceive}: a tap on pause, previous or next arrived. */
    public static void onTap(Context context, Intent intent) {
        try {
            Object action = intent == null ? null : intent.getSerializableExtra(
                    "com.soundcloud.android.HomescreenWidgetBroadcastReceiver.EXTRA_DEFERRED_MEDIA_ACTION");
            Logger.printInfo(() -> "Widget: tap arrived, action " + action + ", intent " + (intent == null ? null : intent.getAction())
                    + ", " + processState());
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the tap", ex);
        }
    }

    /** Injection point. The start of {@code PlayerAppWidgetProvider.onReceive}: the system tells the widget something. */
    public static void onProviderReceive(Context context, Intent intent) {
        try {
            Logger.printInfo(() -> "Widget: the system sent " + (intent == null ? null : intent.getAction()));
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the system's message", ex);
        }
    }

    /** What state the app's process is in when a tap arrives; a process the system froze or limited shows here. */
    private static String processState() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return "process importance " + info.importance + " (100 foreground, 125 foreground service, 400 cached)";
    }

    /** Once per start of the app: what the phone allows the app in the background. */
    private static void logEnvironment(Context context) {
        if (environmentLogged || context == null) return;
        environmentLogged = true;
        StringBuilder text = new StringBuilder("Widget: phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.SDK_INT);
        String hyperOs = property("ro.mi.os.version.name");
        String miui = property("ro.miui.ui.version.name");
        if (!hyperOs.isEmpty()) text.append(", HyperOS ").append(hyperOs).append(' ').append(property("ro.mi.os.version.incremental"));
        else if (!miui.isEmpty()) text.append(", MIUI ").append(miui);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            text.append(", ignores battery optimisation ").append(power.isIgnoringBatteryOptimizations(context.getPackageName()));
        }
        ActivityManager activities = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activities != null && Build.VERSION.SDK_INT >= 28) {
            text.append(", background restricted ").append(activities.isBackgroundRestricted());
        }
        if (Build.VERSION.SDK_INT >= 28) {
            UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage != null) {
                text.append(", standby bucket ").append(usage.getAppStandbyBucket())
                        .append(" (10 active, 20 working, 30 frequent, 40 rare, 45 restricted)");
            }
        }
        if (Build.MANUFACTURER.equalsIgnoreCase("xiaomi") || !hyperOs.isEmpty() || !miui.isEmpty()) {
            text.append(", Xiaomi autostart ").append(xiaomiOp(context, XIAOMI_OP_AUTOSTART))
                    .append(", Xiaomi screens from the background ").append(xiaomiOp(context, XIAOMI_OP_BACKGROUND_START))
                    .append(" (0 allowed, 1 refused, other: not known)");
        }
        String line = text.toString();
        Logger.printInfo(() -> line);
    }

    /** A Xiaomi app operation's mode, read through the hidden number-based call; -1 when it cannot be read. */
    private static int xiaomiOp(Context context, int op) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            Object mode = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class)
                    .invoke(ops, op, Process.myUid(), context.getPackageName());
            return (Integer) mode;
        } catch (Throwable ex) {
            return -1;
        }
    }

    private static String property(String name) {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name));
        } catch (Throwable ex) {
            return "";
        }
    }
}
