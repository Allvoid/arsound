package app.revanced.extension.soundcloud.offline;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.PlaybackStreams;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Retries playback errors on a flaky connection instead of showing "Track cannot be streamed".
 * <p>
 * SoundCloud treats every source error as fatal while Android reports the device as connected,
 * even when the error is a timeout or a dropped connection. Tapping the track again used to fix it.
 * This does the same automatically: Media3 keeps the item and position after an error, so
 * {@code prepare()} continues from where playback stopped.
 */
@SuppressWarnings("unused")
public final class PlaybackRetryPatch {
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2_000;
    /**
     * A failing retry chain produces its next error well within this time (the longest delay is 6 s
     * plus the connection timeout). A longer gap means the last retry worked, and the next error is
     * a new interruption with its own attempts, not the fourth one of the same.
     */
    private static final long BUDGET_RESET_MS = 120_000;
    private static final String PLAYBACK_ITEM = "com.soundcloud.android.playback.core.PlaybackItem";
    private static final String STREAM = "com.soundcloud.android.playback.core.stream.Stream";

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static Object retriedItem;
    private static int attempts;
    /** A retry is posted and has not run yet; main thread only. */
    private static boolean retryPending;
    private static long lastErrorAt;

    private PlaybackRetryPatch() {
    }

    /**
     * Called when the player reports an error.
     *
     * @param player    The {@code BaseExoPlayer}.
     * @param connected Whether Android reports a connection. SoundCloud makes the error fatal if true.
     * @return The connected flag SoundCloud should use. False keeps the error recoverable while retrying.
     */
    public static boolean onPlaybackError(Object player, boolean connected) {
        if (!connected || !Settings.isPlaybackRetryEnabled()) return connected;
        try {
            logError(player);
            Object item = currentItem(player);
            // A missing or damaged file stays that way: waiting would only delay the error.
            if (playsFromFile(player, item)) return connected;

            long now = SystemClock.elapsedRealtime();
            if (item != retriedItem || now - lastErrorAt > BUDGET_RESET_MS) {
                retriedItem = item;
                attempts = 0;
            }
            lastErrorAt = now;
            if (attempts >= MAX_ATTEMPTS) {
                Logger.printInfo(() -> "Playback still failing after " + MAX_ATTEMPTS + " retries");
                return true;
            }

            // A retry already waits: a state reported again meanwhile (a tap on pause) spends no attempt and queues no
            // second prepare.
            if (retryPending) return false;
            attempts++;
            int attempt = attempts;
            Logger.printInfo(() -> "Retrying playback, attempt " + attempt);
            retryPending = true;
            handler.postDelayed(() -> {
                retryPending = false;
                retry(player, item);
            }, RETRY_DELAY_MS * attempt);
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not schedule playback retry", ex);
            return true;
        }
    }

    private static Throwable lastLogged;

    /**
     * Writes what the player failed with (the cause chain, without addresses) into the normal log: until now only
     * "Retrying playback" was there, and a track that never starts could not be told from a blocked or broken stream.
     */
    private static void logError(Object player) {
        try {
            Method exoPlayerGetter = Reflect.method(baseClass(player), method -> method.getParameterTypes().length == 0
                    && method.getReturnType().getName().equals("androidx.media3.exoplayer.ExoPlayer")
                    && !java.lang.reflect.Modifier.isAbstract(method.getModifiers()));
            Object exoPlayer = exoPlayerGetter == null ? null : exoPlayerGetter.invoke(player);
            if (exoPlayer == null) return;
            // getPlayerError(), renamed by R8: the only method without parameters that returns a PlaybackException.
            Class<?> playerInterface = Class.forName("androidx.media3.common.Player", false, exoPlayer.getClass().getClassLoader());
            Method errorGetter = null;
            for (Method method : playerInterface.getMethods()) {
                if (method.getParameterTypes().length == 0
                        && method.getReturnType().getName().equals("androidx.media3.common.PlaybackException")) {
                    errorGetter = method;
                    break;
                }
            }
            if (errorGetter == null) throw new NoSuchMethodException("Player.getPlayerError");
            Throwable error = (Throwable) errorGetter.invoke(exoPlayer);
            if (error == null || error == lastLogged) return;
            lastLogged = error;
            StringBuilder chain = new StringBuilder();
            int depth = 0;
            for (Throwable cause = error; cause != null && depth < 6; cause = cause.getCause(), depth++) {
                if (depth > 0) chain.append(" <- ");
                String message = String.valueOf(cause.getMessage()).replaceAll("https?://([^/\\s?]+)\\S*", "$1");
                if (message.length() > 160) message = message.substring(0, 160);
                chain.append(cause.getClass().getSimpleName()).append(": ").append(message);
            }
            String text = chain.toString();
            Logger.printInfo(() -> "Player error: " + text);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the player error: " + ex);
        }
    }

    private static void retry(Object player, Object item) {
        try {
            // The user may have switched tracks while waiting.
            if (currentItem(player) != item) return;

            Method exoPlayerGetter = Reflect.method(baseClass(player), method -> method.getParameterTypes().length == 0
                    && method.getReturnType().getName().equals("androidx.media3.exoplayer.ExoPlayer")
                    && !java.lang.reflect.Modifier.isAbstract(method.getModifiers()));
            Object exoPlayer = exoPlayerGetter == null ? null : exoPlayerGetter.invoke(player);
            if (exoPlayer == null) {
                giveUp(player);
                return;
            }

            Class<?> playerInterface = Class.forName("androidx.media3.common.Player", false, exoPlayer.getClass().getClassLoader());
            playerInterface.getMethod("prepare").invoke(exoPlayer);
        } catch (Exception ex) {
            Logger.printException(() -> "Playback retry failed", ex);
            giveUp(player);
        }
    }

    /**
     * The retry could not be made. The budget is spent, so the next report of the error (the player reports its error
     * state again on the next change, a tap included) goes through to SoundCloud as fatal instead of waiting again.
     */
    private static void giveUp(Object player) {
        attempts = MAX_ATTEMPTS;
        Logger.printInfo(() -> "Playback retry not possible, the next error report is shown");
    }

    /**
     * Whether the item plays from a local file (downloaded by Arsound or imported). Asks the player
     * which of the item's streams it uses; found by its signature, as R8's names change between versions.
     * If that cannot be found, the item counts as a network one, so retries stay as they were.
     */
    private static boolean playsFromFile(Object player, Object item) {
        if (item == null) return false;
        try {
            for (Method method : baseClass(player).getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 1 && types[0].getName().equals(PLAYBACK_ITEM)
                        && method.getReturnType().getName().equals(STREAM)) {
                    method.setAccessible(true);
                    Object stream = method.invoke(player, item);
                    return PlaybackStreams.isFileStream(stream);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not tell whether the item plays from a file", ex);
        }
        return false;
    }

    private static Class<?> baseClass(Object player) {
        Class<?> type = player.getClass();
        while (type != null && !type.getName().equals("com.soundcloud.android.exoplayer.BaseExoPlayer")) {
            type = type.getSuperclass();
        }
        return type == null ? player.getClass() : type;
    }

    private static Object currentItem(Object player) throws Exception {
        Field field = Reflect.field(baseClass(player), Reflect.typeNamed("com.soundcloud.android.playback.core.PlaybackItem"));
        return field == null ? null : field.get(player);
    }
}
