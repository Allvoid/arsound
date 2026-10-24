package app.revanced.extension.soundcloud.player;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * The play mode button of the media notification: in order, repeat the queue, repeat the track, shuffled.
 * Each tap moves to the next mode. SoundCloud keeps repeat and shuffle on its play queue, and its own buttons
 * for them are deep in the player's queue screen.
 * <p>
 * Android 13 and later draw the media notification from the media session, so the button is a custom action
 * of the playback state there. Older versions draw the actions of the notification itself, so the button is
 * also added to the notification, with a broadcast to {@link PlayModeReceiver}.
 */
@SuppressWarnings("unused")
public final class PlayModeButton {
    /** The custom action of the media session, and the broadcast of the notification action. */
    public static final String ACTION = "app.arsound.action.PLAY_MODE";

    private static final String QUEUE_MANAGER = "com.soundcloud.android.features.playqueue.PlayQueueManager";
    private static final String PLAY_QUEUE = "com.soundcloud.android.foundation.playqueue.PlayQueue";
    private static final String SHUFFLED_QUEUE = PLAY_QUEUE + "$Shuffled";
    private static final String PLAY_QUEUE_ITEM = "com.soundcloud.android.foundation.playqueue.PlayQueueItem";
    private static final String REPEAT_MODE = "com.soundcloud.android.foundation.domain.playqueue.RepeatMode";
    private static final String STATE_BUILDER = "android.support.v4.media.session.PlaybackStateCompat$Builder";
    private static final String STATE = "android.support.v4.media.session.PlaybackStateCompat";
    private static final String NOTIFICATION_ACTION = "androidx.core.app.NotificationCompat$Action";

    private enum Mode {
        ORDER("arsound_play_mode_order", "По порядку", "In order"),
        REPEAT_ALL("arsound_play_mode_repeat_all", "Повтор очереди", "Repeat queue"),
        REPEAT_ONE("arsound_play_mode_repeat_one", "Повтор трека", "Repeat track"),
        SHUFFLE("arsound_play_mode_shuffle", "Вперемешку", "Shuffle");

        final String icon;
        final String russian;
        final String english;

        Mode(String icon, String russian, String english) {
            this.icon = icon;
            this.russian = russian;
            this.english = english;
        }

        String title() {
            return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Object> queueManager = new WeakReference<>(null);
    private static volatile WeakReference<Object> mediaService = new WeakReference<>(null);
    private static volatile WeakReference<Object> notificationManager = new WeakReference<>(null);
    /** The custom actions of the last state handed to the session: the like and the play mode. */
    private static volatile String lastActions;
    /** The last playback state SoundCloud gave the session, before the button was added: sent again after a tap. */
    private static volatile Object lastState;

    private PlayModeButton() {
    }

    /** Injection point. The end of the media notification manager's constructor. */
    public static void setNotificationManager(Object manager) {
        notificationManager = new WeakReference<>(manager);
    }

    /** Injection point. The end of the play queue manager's constructor; the app has one. */
    public static void setQueueManager(Object manager) {
        queueManager = new WeakReference<>(manager);
    }

    // region Android 13 and later: the media session

    /** Injection point. The start of the media service's method that hands a playback state to the session. */
    public static void remember(Object service, Object state) {
        mediaService = new WeakReference<>(service);
        lastState = state;
    }

    private static Method sessionSetState;

    /**
     * Injection point. Replaces the media service's call that hands the playback state to the session. It runs for
     * every position update, so the method is looked up once; a failure to find it is a failure of the patch.
     */
    public static void setPlaybackState(Object session, Object state) throws Exception {
        Method method = sessionSetState;
        if (method == null) {
            method = session.getClass().getMethod("setPlaybackState", Class.forName(STATE));
            sessionSetState = method;
        }
        Object shown = withAction(state);
        method.invoke(session, shown);
        updateNotificationOnChange(shown);
    }

    /**
     * Android 12L and older show the actions of the notification, and SoundCloud rebuilds it only when playback
     * starts or stops. A like from the notification went through, but its heart stayed as it was, and a second tap
     * liked the track again instead of taking the like back. The notification is rebuilt whenever the like or the
     * play mode changes.
     */
    private static void updateNotificationOnChange(Object state) {
        if (android.os.Build.VERSION.SDK_INT >= 33 || state == null) return;
        try {
            String actions = String.valueOf(state.getClass().getMethod("getCustomActions").invoke(state));
            String previous = lastActions;
            lastActions = actions;
            if (previous == null || previous.equals(actions)) return;
            int playback = (int) state.getClass().getMethod("getState").invoke(state);
            // Paused, playing or buffering: the notification is on screen. A stopped one must not come back.
            if (playback != 2 && playback != 3 && playback != 6) return;
            Object manager = notificationManager.get();
            if (manager == null) return;
            main.post(() -> {
                try {
                    manager.getClass().getMethod("d").invoke(manager);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Play mode: could not update the notification", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Play mode: could not compare the notification actions", ex);
        }
    }

    /** @return The state with the button among its custom actions, or the state as it was. */
    private static Object withAction(Object state) {
        if (state == null) return null;
        try {
            Mode mode = currentMode();
            if (mode == null) return state;
            int icon = icon(mode);
            if (icon == 0) return state;
            Class<?> builderClass = Class.forName(STATE_BUILDER);
            Object builder = builderClass.getConstructor(Class.forName(STATE)).newInstance(state);
            builderClass.getMethod("addCustomAction", String.class, String.class, int.class)
                    .invoke(builder, ACTION, mode.title(), icon);
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable ex) {
            Logger.printException(() -> "Play mode: could not add the button to the session", ex);
            return state;
        }
    }

    /**
     * Injection point. The start of SoundCloud's handler of the session's custom actions.
     *
     * @return True if the action was the button's and is handled.
     */
    public static boolean onCustomAction(String action) {
        if (!ACTION.equals(action)) return false;
        main.post(PlayModeButton::cycle);
        return true;
    }

    // endregion

    // region Android 12L and older: the notification

    /** Injection point. Replaces the build of the media notification: adds the button as its last action first. */
    public static android.app.Notification build(Object builder) throws Exception {
        addNotificationAction(builder, Utils.getContext());
        // The builder's build() is obfuscated: it is the method without parameters that returns a notification.
        for (Method method : builder.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() == android.app.Notification.class) {
                return (android.app.Notification) method.invoke(builder);
            }
        }
        throw new IllegalStateException("The notification builder has no build method");
    }

    private static void addNotificationAction(Object builder, Context context) {
        if (builder == null || context == null) return;
        try {
            Mode mode = currentMode();
            if (mode == null) return;
            int icon = icon(mode);
            if (icon == 0) return;
            Intent intent = new Intent(ACTION).setClass(context, PlayModeReceiver.class).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, 0x5079, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Class<?> actionClass = Class.forName(NOTIFICATION_ACTION);
            Constructor<?> constructor = actionClass.getConstructor(int.class, CharSequence.class, PendingIntent.class);
            Object action = constructor.newInstance(icon, mode.title(), pending);
            // The builder's addAction is obfuscated (and returns nothing after shrinking): the method that takes an action.
            for (Method method : builder.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == actionClass) {
                    method.invoke(builder, action);
                    return;
                }
            }
            Logger.printInfo(() -> "Play mode: the notification builder has no method to add an action");
        } catch (Throwable ex) {
            Logger.printException(() -> "Play mode: could not add the button to the notification", ex);
        }
    }

    // endregion

    /** Moves to the next mode. Runs on the main thread: SoundCloud sets play queues only there. */
    static void cycle() {
        Object manager = queueManager.get();
        if (manager == null) return;
        try {
            Mode current = currentMode();
            if (current == null) return;
            Mode next = current.next();
            Class<?> repeatModes = Class.forName(REPEAT_MODE);
            Method setRepeat = manager.getClass().getMethod("E", repeatModes, boolean.class);
            switch (next) {
                case REPEAT_ALL:
                    setRepeat.invoke(manager, repeatMode(repeatModes, "REPEAT_ALL"), true);
                    break;
                case REPEAT_ONE:
                    setRepeat.invoke(manager, repeatMode(repeatModes, "REPEAT_ONE"), true);
                    break;
                case SHUFFLE: {
                    setRepeat.invoke(manager, repeatMode(repeatModes, "REPEAT_NONE"), true);
                    // As SoundCloud's own shuffle button: the tracks after the current one are shuffled.
                    Object queue = queue(manager);
                    int position = (int) manager.getClass().getMethod("i").invoke(manager);
                    int size = (int) queue.getClass().getMethod("size").invoke(queue);
                    Object shuffled = queue.getClass().getMethod("shuffle", int.class)
                            .invoke(queue, position + 1 >= size ? 0 : position + 1);
                    manager.getClass().getMethod("C", Class.forName(PLAY_QUEUE)).invoke(manager, shuffled);
                    break;
                }
                default: {
                    Object queue = queue(manager);
                    if (Class.forName(SHUFFLED_QUEUE).isInstance(queue)) {
                        keepAddedItems(queue);
                        manager.getClass().getMethod("F").invoke(manager);
                    }
                    setRepeat.invoke(manager, repeatMode(repeatModes, "REPEAT_NONE"), true);
                    break;
                }
            }
            Logger.printDebug(() -> "Play mode: " + current + " -> " + next);
            refresh();
        } catch (Throwable ex) {
            Logger.printException(() -> "Play mode: could not change the mode", ex);
        }
    }

    /**
     * Tracks added after shuffling (autoplay recommendations, "Play next") are only in the shuffled queue.
     * SoundCloud's unshuffle goes back to the queue from before the shuffle and throws if the playing track is not
     * in it, so the mode stayed on "Shuffle" once autoplay had started. The added tracks are appended to that
     * queue first, in the order they play now.
     */
    private static void keepAddedItems(Object shuffled) throws Exception {
        Object original = shuffled.getClass().getMethod("getOriginalQueue").invoke(shuffled);
        Method indexOf = original.getClass().getMethod("indexOfPlayQueueItem", Class.forName(PLAY_QUEUE_ITEM));
        java.util.List<Object> added = new java.util.ArrayList<>();
        for (Object item : (java.util.List<?>) shuffled.getClass().getMethod("items").invoke(shuffled)) {
            if ((int) indexOf.invoke(original, item) < 0) added.add(item);
        }
        if (added.isEmpty()) return;
        original.getClass().getMethod("addAllPlayQueueItems", java.util.List.class, String.class)
                .invoke(original, added, "reco");
        Logger.printDebug(() -> "Play mode: kept " + added.size() + " tracks added after shuffling");
    }

    /** Sends the last playback state again, so the session and the notification show the new mode. */
    private static void refresh() {
        Object service = mediaService.get();
        Object state = lastState;
        if (service == null || state == null) return;
        try {
            service.getClass().getMethod("p", Class.forName(STATE)).invoke(service, state);
        } catch (Throwable ex) {
            Logger.printException(() -> "Play mode: could not refresh the notification", ex);
        }
    }

    /** The mode of the current play queue, or null if there is no queue yet. */
    private static Mode currentMode() throws Exception {
        Object manager = queueManager.get();
        if (manager == null) return null;
        Object queue = queue(manager);
        if (queue == null || (int) queue.getClass().getMethod("size").invoke(queue) == 0) return null;
        if (Class.forName(SHUFFLED_QUEUE).isInstance(queue)) return Mode.SHUFFLE;
        String repeat = String.valueOf(queue.getClass().getMethod("getRepeatMode").invoke(queue));
        if ("REPEAT_ALL".equals(repeat)) return Mode.REPEAT_ALL;
        if ("REPEAT_ONE".equals(repeat)) return Mode.REPEAT_ONE;
        return Mode.ORDER;
    }

    private static Object queue(Object manager) throws Exception {
        return manager.getClass().getMethod("k").invoke(manager);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object repeatMode(Class<?> repeatModes, String name) {
        return Enum.valueOf((Class) repeatModes, name);
    }

    private static int icon(Mode mode) {
        return Utils.getResourceIdentifier(ResourceType.DRAWABLE, mode.icon);
    }
}
