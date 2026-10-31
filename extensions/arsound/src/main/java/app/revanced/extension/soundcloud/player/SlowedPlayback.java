package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Plays every track slowed: slower and lower, as the "slowed" versions are made. The speed of the player is set when a
 * track starts, with the pitch following the speed, so it is the same as a record played slower. A track whose title
 * already says that it was slowed or sped up is left at its own speed: slowed twice it would be mush.
 * <p>
 * The player is Media3's, behind SoundCloud's wrapper; both the way to it and the call that sets the speed are found by
 * their shape, because their names are shrunk.
 */
public final class SlowedPlayback {
    /** The speed of the slowed tracks in percent: 85 is the usual "slowed". */
    public static final int DEFAULT_PERCENT = 85;
    public static final long[] PERCENTS = {90, 85, 80, 75, 70, 65};

    /** Words of a title that mean the track is already at another speed. */
    private static final Pattern RETIMED = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:slowed|slow\\s*(?:\\+|&|and|n)\\s*reverb|sped[\\s-]*up|speed[\\s-]*up|speedup|nightcore|"
                    + "daycore|8d|замедлен\\p{L}*|ускорен\\p{L}*|слоу\\p{L}*|найткор\\p{L}*)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Object lock = new Object();
    private static WeakReference<Object> player = new WeakReference<>(null);
    private static Handler handler;
    /** The item that started last, and whether its title says it is at another speed already. */
    private static String urn;
    private static boolean alreadyRetimed;

    /** How to get to the Media3 player, per wrapper class: the streams of HLS and of files are other classes. */
    private static final Map<Class<?>, Method> playerOf = new HashMap<>();
    private static Method setParameters;
    private static Constructor<?> parameters;

    private SlowedPlayback() {
    }

    public static boolean isEnabled() {
        return BoolSettings.SLOWED_PLAYBACK.get();
    }

    /** The speed of slowed tracks as a factor of their own. */
    public static float speed() {
        long percent = Settings.getLong(Settings.SLOWED_PERCENT, DEFAULT_PERCENT);
        return Math.max(50, Math.min(100, percent)) / 100f;
    }

    /** Whether the title says that the track is already slowed or sped up. */
    public static boolean isRetimed(String title) {
        return title != null && RETIMED.matcher(title).find();
    }

    /**
     * A new item starts in the player ({@link PlaybackEvents#onPlay}). Its title is read from SoundCloud's own database,
     * which works without a network; a title that is not there yet leaves the track to be slowed.
     */
    static void onNewItem(Object exoPlayer, String itemUrn) {
        try {
            boolean retimed = isEnabled() && titleSaysRetimed(itemUrn);
            Handler target;
            synchronized (lock) {
                if (handler == null || player.get() != exoPlayer) {
                    Looper looper = Looper.myLooper();
                    handler = new Handler(looper != null ? looper : Looper.getMainLooper());
                }
                player = new WeakReference<>(exoPlayer);
                urn = itemUrn;
                alreadyRetimed = retimed;
                target = handler;
            }
            // After SoundCloud has started the item: the speed is the player's own and stays for the items that follow.
            target.post(SlowedPlayback::apply);
        } catch (Throwable ex) {
            Logger.printException(() -> "Slowed playback: could not follow the new track", ex);
        }
    }

    /** The setting was changed: the track that plays now changes speed at once. */
    public static void onSettingChanged() {
        Handler target;
        synchronized (lock) {
            target = handler;
            if (urn != null) alreadyRetimed = isEnabled() && titleSaysRetimed(urn);
        }
        if (target != null) target.post(SlowedPlayback::apply);
    }

    private static boolean titleSaysRetimed(String itemUrn) {
        try {
            File local = LocalAdditions.importedFileOf(itemUrn);
            if (local != null) {
                LocalMusic.Track track = local.isFile() ? LocalMusic.track(local) : null;
                return isRetimed(track != null ? track.title : local.getName());
            }
            if (!itemUrn.contains(":tracks:")) return false;
            Map<String, String[]> titles = TrackTitleResolver.storedTitles(Collections.singletonList(itemUrn));
            String[] found = titles.get(itemUrn);
            return found != null && isRetimed(found[0]);
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Slowed playback: the title is not known: " + ex);
            return false;
        }
    }

    /** Sets the speed the current item should have. Runs on the player's thread. */
    private static void apply() {
        Object base;
        boolean slowed;
        synchronized (lock) {
            base = player.get();
            // Ads and the like are not tracks and keep their speed.
            slowed = isEnabled() && !alreadyRetimed && urn != null && (urn.contains(":tracks:") || LocalAdditions.importedFileOf(urn) != null);
        }
        if (base == null) return;
        float factor = slowed ? speed() : 1f;
        try {
            Object exo = exoPlayer(base);
            if (exo == null) return;
            if (setParameters == null) resolve(exo.getClass().getClassLoader());
            // The pitch follows the speed: slower and lower, as a record played slower.
            setParameters.invoke(exo, parameters.newInstance(factor, factor));
            Logger.printInfo(() -> "Slowed playback: " + (slowed ? "slowed to " : "normal speed, ") + factor + " for " + urn
                    + (alreadyRetimed ? " (its title says it is at another speed already)" : ""));
        } catch (Throwable ex) {
            Logger.printException(() -> "Slowed playback: could not set the speed", ex);
        }
    }

    /** SoundCloud's wrapper keeps the Media3 player behind a method without parameters: found by the type it returns. */
    private static Object exoPlayer(Object base) throws Exception {
        Method method = playerOf.get(base.getClass());
        if (method == null) {
            Class<?> playerType = Class.forName("androidx.media3.common.Player", false, base.getClass().getClassLoader());
            for (Method candidate : base.getClass().getMethods()) {
                if (candidate.getParameterTypes().length == 0 && playerType.isAssignableFrom(candidate.getReturnType())) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) throw new NoSuchMethodException("the Media3 player of " + base.getClass().getName());
            playerOf.put(base.getClass(), method);
        }
        return method.invoke(base);
    }

    /** {@code Player.setPlaybackParameters(PlaybackParameters)} and the constructor of the parameters, found by their shape. */
    private static void resolve(ClassLoader loader) throws Exception {
        Class<?> playerType = Class.forName("androidx.media3.common.Player", false, loader);
        Class<?> parametersType = Class.forName("androidx.media3.common.PlaybackParameters", false, loader);
        Method found = null;
        for (Method method : playerType.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 1 && types[0] == parametersType && method.getReturnType() == void.class) found = method;
        }
        if (found == null) throw new NoSuchMethodException("Player.setPlaybackParameters");
        parameters = parametersType.getConstructor(float.class, float.class);
        setParameters = found;
    }
}
