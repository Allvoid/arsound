package app.revanced.extension.soundcloud.offline;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

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

            attempts++;
            int attempt = attempts;
            Logger.printInfo(() -> "Retrying playback, attempt " + attempt);
            handler.postDelayed(() -> retry(player, item), RETRY_DELAY_MS * attempt);
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not schedule playback retry", ex);
            return true;
        }
    }

    private static void retry(Object player, Object item) {
        try {
            // The user may have switched tracks while waiting.
            if (currentItem(player) != item) return;

            Object exoPlayer = null;
            for (Method method : baseClass(player).getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0
                        && method.getReturnType().getName().equals("androidx.media3.exoplayer.ExoPlayer")
                        && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    method.setAccessible(true);
                    exoPlayer = method.invoke(player);
                    break;
                }
            }
            if (exoPlayer == null) return;

            Class<?> playerInterface = Class.forName("androidx.media3.common.Player", false, exoPlayer.getClass().getClassLoader());
            playerInterface.getMethod("prepare").invoke(exoPlayer);
        } catch (Exception ex) {
            Logger.printException(() -> "Playback retry failed", ex);
        }
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
                    return stream != null && stream.getClass().getName().endsWith("Stream$FileStream");
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
        for (Field field : baseClass(player).getDeclaredFields()) {
            if (field.getType().getName().equals("com.soundcloud.android.playback.core.PlaybackItem")) {
                field.setAccessible(true);
                return field.get(player);
            }
        }
        return null;
    }
}
