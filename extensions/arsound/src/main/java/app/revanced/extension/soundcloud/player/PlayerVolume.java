package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;

/**
 * SoundCloud's player volume ({@code BaseExoPlayer.setVolume}), not the phone's: the volume buttons are untouched.
 * More than one feature lowers it: the smooth transition between tracks ({@link Crossfade}) and the fade at the end of
 * the sleep timer ({@link SleepFade}). Each sets a level of its own from 0 to 1, and the player gets their product, so
 * a transition during the sleep fade does not jump back to full volume.
 * <p>
 * The player is bound when an item starts ({@link PlaybackEvents#onPlay}); its volume is set on the player's own
 * thread, which {@link #handler} runs on.
 */
final class PlayerVolume {
    /** The features that set a level. */
    enum Source {
        CROSSFADE,
        SLEEP
    }

    private static final Object lock = new Object();
    private static final float[] levels = new float[Source.values().length];
    private static Handler handler;
    private static Object player;
    private static Method setVolume;
    /** The last volume set on the player; 1 when nothing is touched. */
    private static float applied = 1f;

    static {
        java.util.Arrays.fill(levels, 1f);
    }

    private PlayerVolume() {
    }

    /** An item starts on this player. Runs on the player's thread. */
    static void bind(Object exoPlayer) throws NoSuchMethodException {
        synchronized (lock) {
            if (handler != null && player == exoPlayer) return;
            Looper looper = Looper.myLooper();
            handler = new Handler(looper != null ? looper : Looper.getMainLooper());
            setVolume = exoPlayer.getClass().getMethod("setVolume", float.class);
            player = exoPlayer;
        }
    }

    /** The handler of the player's thread, or null before any item started. */
    static Handler handler() {
        synchronized (lock) {
            return handler;
        }
    }

    /** The volume the levels ask for together. */
    static float product(float... parts) {
        float result = 1f;
        for (float part : parts) result *= Math.max(0f, Math.min(1f, part));
        return result;
    }

    /**
     * Sets the level of one feature and puts the product of all levels on the player. Call it on the player's thread.
     * Full volume is set once; a lower volume is set on every call: SoundCloud sets full volume itself when an item
     * starts, and the lower level must win over it at once.
     */
    static void set(Source source, float level) {
        float target;
        Object current;
        Method method;
        synchronized (lock) {
            levels[source.ordinal()] = level;
            target = product(levels);
            if (target >= 1f && applied >= 1f) return;
            applied = target;
            current = player;
            method = setVolume;
        }
        if (current == null) return;
        try {
            method.invoke(current, target);
        } catch (Throwable ex) {
            Logger.printException(() -> "Player volume: could not set it", ex);
        }
    }

    /** {@link #set} from any thread: it runs on the player's thread. Before any item started only the level is kept. */
    static void post(Source source, float level) {
        Handler current;
        synchronized (lock) {
            current = handler;
            if (current == null) {
                levels[source.ordinal()] = level;
                return;
            }
        }
        current.post(() -> set(source, level));
    }

    /** The level one feature asks for now. */
    static float level(Source source) {
        synchronized (lock) {
            return levels[source.ordinal()];
        }
    }
}
