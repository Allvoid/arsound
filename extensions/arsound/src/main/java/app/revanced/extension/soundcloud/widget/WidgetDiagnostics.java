package app.revanced.extension.soundcloud.widget;

import android.app.ActivityManager;
import android.app.Application;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.soundcloud.player.PlayModeReceiver;

import app.revanced.extension.shared.Logger;

/**
 * Lines in the log about the player widget on the home screen, for the log the user sends when a button of the widget
 * does nothing. A tap on the widget goes launcher -> system -> the app's process; a phone (Xiaomi's HyperOS is the known
 * one) can stop it at any of those steps, and a stopped tap leaves no trace in the app. So the lines say, for each step,
 * what can be known afterwards:
 * <ul>
 * <li>why the process was started and whether it was started by the tap (Android 15's start history), and why the
 * earlier ones ended (the crash section of the saved file has that);</li>
 * <li>that the tap arrived, from whom, whether the process had only just been started for it, and what the player
 * did with it a moment later;</li>
 * <li>that the widget was built with its buttons, that the system still holds the buttons' intents, the widgets
 * on the home screen with their sizes, the state of the widget's components, and what the phone allows the app in
 * the background (on Xiaomi the autostart and the "pop-up windows in the background" permissions decide whether a tap
 * reaches the app).</li>
 * </ul>
 * A tap with no "tap arrived" line after a "tap" the user made was stopped before the app, by the launcher or the
 * system; the start history of the next process tells whether the system started it for that tap.
 * <p>
 * The events go to the log and to {@link WidgetLog}, which is kept whatever the log settings are.
 */
@SuppressWarnings("unused")
public final class WidgetDiagnostics {
    /** Xiaomi's own app operations: autostart, and starting a screen from the background. */
    private static final int XIAOMI_OP_AUTOSTART = 10008;
    private static final int XIAOMI_OP_BACKGROUND_START = 10021;

    private static final String PLAYER_PROVIDER = "com.soundcloud.android.playback.widget.service.PlayerAppWidgetProvider";
    private static final String TAP_RECEIVER = "com.soundcloud.android.playback.widget.HomescreenWidgetBroadcastReceiver";
    private static final String TAP_ACTION = "com.soundcloud.android.HomescreenWidgetBroadcastReceiver.ACTION";
    private static final String DEFERRED_ACTION_EXTRA = "com.soundcloud.android.HomescreenWidgetBroadcastReceiver.EXTRA_DEFERRED_MEDIA_ACTION";
    /** SoundCloud's request codes of the buttons that send a broadcast. */
    private static final int[] BUTTON_CODES = {2, 8, 4};
    private static final String[] BUTTON_NAMES = {"pause", "previous", "next"};

    /** A process started for less than this long ago was most likely started by the tap itself. */
    private static final long JUST_STARTED_MS = 5_000;
    /** How long after a tap the player is asked once more what it is doing. */
    private static final long RECHECK_MS = 1_500;

    private static volatile boolean environmentLogged;
    private static volatile boolean updateLogged;
    /** The state the buttons were last built for in this process; null before the first build. */
    private static volatile Boolean lastWiredPlaying;

    private WidgetDiagnostics() {
    }

    /**
     * Called at the start of every process of the app, from {@code Application.onCreate}: says why the process was
     * started and what the widgets look like. Done on a thread of its own, not to slow the start.
     */
    public static void onApplicationStart(Application application) {
        try {
            Thread thread = new Thread(() -> logProcessStart(application), "arsound-widget-diagnostics");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the start", ex);
        }
    }

    /** Injection point. The start of {@code PlayerWidgetRemoteViews.a(Context, boolean)}: the buttons are wired for the state. */
    public static void onButtonsWired(Context context, boolean playing) {
        try {
            logEnvironment(context);
            String text = "buttons wired, playing " + playing + ", the play button "
                    + (playing ? "sends a broadcast (pause)" : "opens the app (play)") + ", previous and next send broadcasts";
            Logger.printInfo(() -> "Widget: " + text);
            // The widget is built at every change of track; the file gets the first build and each change of the state.
            Boolean before = lastWiredPlaying;
            lastWiredPlaying = playing;
            if (before == null || before != playing) WidgetLog.append(context, text);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the buttons", ex);
        }
    }

    /**
     * Injection point. The start of {@code HomescreenWidgetBroadcastReceiver.onReceive}: a tap on pause, previous or
     * next arrived. The receiver is not yet filled by the app's injection here, so what the player did is read right
     * after the method ends and once more a moment later.
     */
    public static void onTap(BroadcastReceiver receiver, Context context, Intent intent) {
        try {
            Object action = intent == null ? null : intent.getSerializableExtra(DEFERRED_ACTION_EXTRA);
            long age = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
            String sender = "unknown";
            if (Build.VERSION.SDK_INT >= 34) sender = receiver.getSentFromPackage() + " uid " + receiver.getSentFromUid();
            String line = "tap arrived: action " + action + ", intent " + (intent == null ? null : intent.getAction())
                    + ", sent from " + sender
                    + ", process started " + age + " ms ago" + (age < JUST_STARTED_MS ? " (started by this tap or just before it)" : "")
                    + ", " + processState()
                    + ", the widget was showing " + describePlaying(lastWiredPlaying, "unknown: the process is new");
            Logger.printInfo(() -> "Widget: " + line);
            WidgetLog.append(context, line);

            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> logOutcome(context, receiver, "tap handled"));
            handler.postDelayed(() -> logOutcome(context, receiver, "tap " + RECHECK_MS + " ms later"), RECHECK_MS);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the tap", ex);
        }
    }

    /** Injection point. The start of {@code PlayerAppWidgetProvider.onReceive}: the system tells the widget something. */
    public static void onProviderReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            Logger.printInfo(() -> "Widget: the system sent " + action);
            // A refresh comes with every change of track: the file gets the first one of a process and everything else
            // (the widget added, removed, resized), which is what the file is for.
            if (!AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action) || !updateLogged) {
                if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) updateLogged = true;
                WidgetLog.append(context, "the system sent " + action);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the system's message", ex);
        }
    }

    // ---- what the player did with a tap ----

    private static void logOutcome(Context context, BroadcastReceiver receiver, String when) {
        try {
            String line = when + ": " + outcome(receiver);
            Logger.printInfo(() -> "Widget: " + line);
            WidgetLog.append(context, line);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log what the tap did", ex);
        }
    }

    /** Whether the receiver got its parts from the app's injection and what the player's state says. */
    private static String outcome(Object receiver) {
        try {
            for (Field field : receiver.getClass().getDeclaredFields()) {
                if (!field.getType().getName().endsWith("PlaySessionStateProvider")) continue;
                field.setAccessible(true);
                Object provider = field.get(receiver);
                if (provider == null) return "the receiver was not filled by the app's injection (the tap did nothing)";
                Object playing = field.getType().getMethod("isPlaying").invoke(provider);
                return "the player says playing " + playing;
            }
            return "no player state found in the receiver";
        } catch (Throwable ex) {
            return "could not read the player state (" + ex + ")";
        }
    }

    private static String describePlaying(Boolean playing, String unknown) {
        return playing == null ? unknown : (playing ? "pause (playing)" : "play (not playing)");
    }

    /** What state the app's process is in when a tap arrives; a process the system froze or limited shows here. */
    private static String processState() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return "process importance " + info.importance + " (100 foreground, 125 foreground service, 400 cached)";
    }

    // ---- delivery self-test ----

    /** The action of the test broadcast; the app's own receiver ({@link PlayModeReceiver}) hands it to {@link #onSelfTest}. */
    public static final String SELF_TEST_ACTION = "app.revanced.extension.soundcloud.widget.SELF_TEST";
    private static final String SELF_TEST_ID = "id";
    private static final int SELF_TEST_REQUEST_CODE = 0x5E1F;
    /** How long a test broadcast is waited for; the receiver runs on the main thread, which can be busy at a start. */
    private static final long SELF_TEST_WAIT_MS = 8_000;
    private static final AtomicInteger selfTestCounter = new AtomicInteger();
    /** Test id to the time its broadcast arrived (elapsed realtime). */
    private static final Map<String, Long> selfTestArrived = new ConcurrentHashMap<>();

    /** Called by the app's own receiver when a test broadcast arrives, on the main thread. */
    public static void onSelfTest(Context context, Intent intent) {
        try {
            String id = intent == null ? null : intent.getStringExtra(SELF_TEST_ID);
            if (id != null) selfTestArrived.put(id, SystemClock.elapsedRealtime());
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not note the test broadcast", ex);
        }
    }

    /**
     * Sends the app's own receiver a harmless broadcast two ways, as a plain broadcast and through a PendingIntent
     * (the way the launcher sends a tap), and waits for both. Tells whether the phone lets a broadcast reach the app at
     * all, which is what a tap on the widget needs; a tap that never arrives while this passes was stopped by the
     * launcher, one that fails together with this was stopped by the system. Blocks up to
     * {@value #SELF_TEST_WAIT_MS} ms: call it off the main thread, which the receiver needs. The result is also written
     * to the log and to the widget's file.
     */
    public static String runSelfTest(Context context, String when) {
        String result;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                result = "not run on the main thread";
            } else {
                String base = Process.myPid() + "-" + selfTestCounter.incrementAndGet();
                String plain = base + "-plain";
                String pending = base + "-pending";
                long sent = SystemClock.elapsedRealtime();
                String problem = "";
                try {
                    context.sendBroadcast(selfTestIntent(context, plain));
                } catch (Throwable ex) {
                    problem += ", the plain broadcast could not be sent (" + ex + ")";
                }
                try {
                    PendingIntent.getBroadcast(context, SELF_TEST_REQUEST_CODE, selfTestIntent(context, pending),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE).send();
                } catch (Throwable ex) {
                    problem += ", the PendingIntent could not be sent (" + ex + ")";
                }
                while (SystemClock.elapsedRealtime() - sent < SELF_TEST_WAIT_MS
                        && !(selfTestArrived.containsKey(plain) && selfTestArrived.containsKey(pending))) {
                    Thread.sleep(25);
                }
                Long plainAt = selfTestArrived.remove(plain);
                Long pendingAt = selfTestArrived.remove(pending);
                result = "plain broadcast " + (plainAt == null ? "NOT delivered in " + SELF_TEST_WAIT_MS + " ms"
                        : "delivered in " + (plainAt - sent) + " ms")
                        + ", through a PendingIntent " + (pendingAt == null ? "NOT delivered in " + SELF_TEST_WAIT_MS + " ms"
                        : "delivered in " + (pendingAt - sent) + " ms")
                        + ", " + processState() + problem;
            }
        } catch (Throwable ex) {
            result = "failed (" + ex + ")";
        }
        String line = "delivery self-test (" + when + "): " + result;
        Logger.printInfo(() -> "Widget: " + line);
        WidgetLog.append(context, line);
        return line;
    }

    private static Intent selfTestIntent(Context context, String id) {
        return new Intent(SELF_TEST_ACTION).setClass(context, PlayModeReceiver.class)
                .setPackage(context.getPackageName()).putExtra(SELF_TEST_ID, id);
    }

    // ---- once per process ----

    private static void logProcessStart(Application application) {
        try {
            String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
            boolean main = process == null || process.isEmpty() || process.equals(application.getPackageName());
            List<String> starts = describeStarts(application, 1);
            String line = "process start: " + (main ? "main process" : process) + ", pid " + Process.myPid()
                    + (starts.isEmpty() ? "" : ", " + starts.get(0));
            Logger.printInfo(() -> "Widget: " + line);
            WidgetLog.append(application, line);
            // The rest says something about the phone and the widgets, which the main process knows as well as any.
            if (!main) return;
            logEnvironment(application);
            String widgets = describeWidgets(application);
            Logger.printInfo(() -> "Widget: " + widgets);
            WidgetLog.append(application, widgets);
            // Whether the phone lets a broadcast reach the app's own receiver at all (the same two ways a tap would).
            runSelfTest(application, "process start");
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget: could not log the start", ex);
        }
    }

    /** Once per start of the app: what the phone allows the app in the background. */
    private static void logEnvironment(Context context) {
        if (environmentLogged || context == null) return;
        environmentLogged = true;
        String line = describePhone(context);
        Logger.printInfo(() -> "Widget: " + line);
        WidgetLog.append(context, line);
    }

    // ---- descriptions, also used by the saved file's widget section ----

    /** The phone, its firmware and what it allows the app in the background. */
    public static String describePhone(Context context) {
        StringBuilder text = new StringBuilder("phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.SDK_INT).append(", build ").append(Build.DISPLAY);
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
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage != null && Build.VERSION.SDK_INT >= 28) {
            text.append(", standby bucket ").append(usage.getAppStandbyBucket())
                    .append(" (10 active, 20 working, 30 frequent, 40 rare, 45 restricted)");
        }
        if (Build.MANUFACTURER.equalsIgnoreCase("xiaomi") || !hyperOs.isEmpty() || !miui.isEmpty()) {
            text.append(", Xiaomi autostart ").append(xiaomiOp(context, XIAOMI_OP_AUTOSTART))
                    .append(", Xiaomi screens from the background ").append(xiaomiOp(context, XIAOMI_OP_BACKGROUND_START))
                    .append(" (0 allowed, 1 refused, other: not known)");
        }
        return text.toString();
    }

    /** The widgets of the app that are on the home screen: their ids, sizes and whether the system knows their provider. */
    public static String describeWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        StringBuilder text = new StringBuilder("on the home screen");
        String[] providers = {PLAYER_PROVIDER, SquarePlayerWidget.class.getName()};
        String[] names = {"SoundCloud player", "Arsound 2x2"};
        for (int i = 0; i < providers.length; i++) {
            ComponentName provider = new ComponentName(context.getPackageName(), providers[i]);
            int[] ids = manager.getAppWidgetIds(provider);
            text.append("; ").append(names[i]).append(": ").append(componentState(context, provider))
                    .append(", ").append(ids == null ? 0 : ids.length).append(" placed");
            if (ids == null) continue;
            for (int id : ids) {
                Bundle options = manager.getAppWidgetOptions(id);
                AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                text.append(" [id ").append(id).append(": ")
                        .append(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)).append('x')
                        .append(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).append(" to ")
                        .append(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).append('x')
                        .append(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)).append(" dp")
                        .append(info == null ? ", the system has no provider for it" : "").append(']');
            }
        }
        text.append("; the buttons' intents the system holds: ").append(describeButtonIntents(context));
        text.append("; the receiver of the taps: ").append(describeTapReceiver(context));
        return text.toString();
    }

    /** Whether the system still holds the intents the widget's buttons send; a button whose intent is gone does nothing. */
    public static String describeButtonIntents(Context context) {
        Intent intent = new Intent(TAP_ACTION).setPackage(context.getPackageName())
                .setComponent(new ComponentName(context.getPackageName(), TAP_RECEIVER));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < BUTTON_CODES.length; i++) {
            PendingIntent held = PendingIntent.getBroadcast(context, BUTTON_CODES[i], intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (i > 0) text.append(", ");
            text.append(BUTTON_NAMES[i]).append(held != null ? " yes" : " no");
        }
        return text.toString();
    }

    private static String describeTapReceiver(Context context) {
        ComponentName receiver = new ComponentName(context.getPackageName(), TAP_RECEIVER);
        try {
            ActivityInfo info = context.getPackageManager().getReceiverInfo(receiver, 0);
            return componentState(context, receiver) + ", exported " + info.exported + ", process " + info.processName
                    + ", permission " + info.permission;
        } catch (Throwable ex) {
            return "not found (" + ex + ")";
        }
    }

    private static String componentState(Context context, ComponentName component) {
        int state = context.getPackageManager().getComponentEnabledSetting(component);
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: return "enabled by default";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: return "DISABLED";
            default: return "state " + state;
        }
    }

    /** The home screen app (HyperOS has its own, and its version differs from phone to phone). */
    public static String describeLauncher(Context context) throws Exception {
        PackageManager packages = context.getPackageManager();
        ResolveInfo home = packages.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null) return "not found";
        String name = home.activityInfo.packageName;
        PackageInfo info = packages.getPackageInfo(name, 0);
        return name + " " + info.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")";
    }

    /**
     * The newest process starts of the app, newest first, with why each one started: from Android 15 the system keeps
     * that. A start whose reason is a broadcast, with the widget's action as its intent, is a tap that woke the app.
     */
    public static List<String> describeStarts(Context context, int count) {
        List<String> lines = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 35) {
            lines.add("the start history needs Android 15");
            return lines;
        }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Method history = ActivityManager.class.getMethod("getHistoricalProcessStartReasons", int.class);
            List<?> starts = (List<?>) history.invoke(manager, count);
            if (starts == null || starts.isEmpty()) lines.add("the start history is empty");
            else for (Object start : starts) lines.add(describeStart(start));
        } catch (Throwable ex) {
            lines.add("could not read the start history (" + ex + ")");
        }
        return lines;
    }

    /** One entry of the start history. Read by name: the class is newer than the one the extension is built against. */
    private static String describeStart(Object start) {
        Class<?> type = start.getClass();
        StringBuilder text = new StringBuilder("started by ");
        text.append(constantName(type, "START_REASON_", callInt(start, "getReason")));
        text.append(", ").append(constantName(type, "START_TYPE_", callInt(start, "getStartType"))).append(" start");
        text.append(", ").append(constantName(type, "STARTUP_STATE_", callInt(start, "getStartupState")));
        text.append(", pid ").append(callInt(start, "getPid"));
        String process = callString(start, "getProcessName");
        if (process != null) text.append(", ").append(process);
        try {
            Object stopped = type.getMethod("wasForceStopped").invoke(start);
            if (Boolean.TRUE.equals(stopped)) text.append(", was force-stopped before");
        } catch (Throwable ignored) {
            // Not every version has it.
        }
        try {
            Intent launched = (Intent) type.getMethod("getIntent").invoke(start);
            if (launched != null) {
                text.append(", intent ").append(launched.getAction()).append(' ').append(launched.getComponent());
            }
        } catch (Throwable ignored) {
            // Not every version has it.
        }
        return text.toString();
    }

    /** The name of the constant of the class with the prefix and the value, or the number when there is none. */
    static String constantName(Class<?> type, String prefix, int value) {
        for (Field field : type.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
            if (!field.getName().startsWith(prefix)) continue;
            try {
                if (field.getInt(null) == value) return field.getName().substring(prefix.length());
            } catch (Exception ignored) {
                // Try the next one.
            }
        }
        return String.valueOf(value);
    }

    private static int callInt(Object target, String method) {
        try {
            return (Integer) target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ex) {
            return -1;
        }
    }

    private static String callString(Object target, String method) {
        try {
            return (String) target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ex) {
            return null;
        }
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
