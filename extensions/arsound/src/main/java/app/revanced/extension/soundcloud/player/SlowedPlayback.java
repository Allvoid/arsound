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
import app.revanced.extension.shared.Utils;
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
    /** The item that started last, and whether its title says it is at another speed already (null until read). */
    private static String urn;
    private static Boolean alreadyRetimed;
    /** Bumped by every read of the title, so a read that finishes late (the setting changed meanwhile) is dropped. */
    private static int titleReads;
    /** Whether the speed of the player is one set here: only then normal speed is put back. Player thread. */
    private static boolean slowedHere;

    /** How to get to the Media3 player, per wrapper class: the streams of HLS and of files are other classes. */
    private static final Map<Class<?>, Method> playerOf = new HashMap<>();
    private static Method setParameters;
    /** {@code Player.getPlaybackParameters()}; null if not found, then the speed is set without a look first. */
    private static Method getParameters;
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
     * An item starts or resumes in the player ({@link PlaybackEvents#onPlay}). For a new track its title is read from
     * SoundCloud's own database, which works without a network, off the player's thread; a title that is not there yet
     * leaves the track to be slowed. The same track on another wrapper (a stream that became a file) has a player of its
     * own, which gets the speed too.
     */
    static void onPlay(Object exoPlayer, String itemUrn) {
        try {
            Handler target;
            boolean newTrack;
            synchronized (lock) {
                newTrack = !itemUrn.equals(urn);
                if (!newTrack && player.get() == exoPlayer) return;
                if (handler == null || player.get() != exoPlayer) {
                    Looper looper = Looper.myLooper();
                    handler = new Handler(looper != null ? looper : Looper.getMainLooper());
                }
                player = new WeakReference<>(exoPlayer);
                urn = itemUrn;
                if (newTrack) alreadyRetimed = null;
                target = handler;
            }
            if (!newTrack) {
                target.post(SlowedPlayback::apply);
                return;
            }
            // After SoundCloud has started the item: the speed is the player's own and stays for the items that follow.
            readTitle(itemUrn, target);
        } catch (Throwable ex) {
            Logger.printException(() -> "Slowed playback: could not follow the new track", ex);
        }
    }

    private static volatile long reassertedAt;

    /**
     * Called about twice a second while playing. SoundCloud's player can put the speed back to normal after the item
     * started, so while slowed is on the speed is looked at again every few seconds and set if it was lost. With slowed
     * off the speed is left alone.
     */
    static void reassert() {
        if (!isEnabled()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - reassertedAt < 3000) return;
        reassertedAt = now;
        Handler target;
        synchronized (lock) {
            target = handler;
        }
        if (target != null) target.post(() -> apply(true));
    }

    /** The setting was changed: the track that plays now changes speed at once. */
    public static void onSettingChanged() {
        Handler target;
        String itemUrn;
        synchronized (lock) {
            target = handler;
            itemUrn = urn;
        }
        if (target == null || itemUrn == null) return;
        readTitle(itemUrn, target);
    }

    /** Reads whether the title says the track is at another speed already, off the player's thread, then applies. */
    private static void readTitle(String itemUrn, Handler target) {
        int read;
        synchronized (lock) {
            read = ++titleReads;
        }
        Utils.runOnBackgroundThread(() -> {
            boolean retimed = isEnabled() && titleSaysRetimed(itemUrn);
            synchronized (lock) {
                if (read != titleReads || !itemUrn.equals(urn)) return;
                alreadyRetimed = retimed;
            }
            target.post(SlowedPlayback::apply);
        });
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

    private static void apply() {
        apply(false);
    }

    /**
     * Sets the speed the current item should have. Runs on the player's thread.
     *
     * @param again A repeat of an earlier apply: the log tells only if the speed had to be set again.
     */
    private static void apply(boolean again) {
        Object base;
        boolean slowed;
        Boolean retimed;
        synchronized (lock) {
            base = player.get();
            retimed = alreadyRetimed;
            // Ads and the like are not tracks and keep their speed.
            slowed = isEnabled() && Boolean.FALSE.equals(retimed) && urn != null
                    && (urn.contains(":tracks:") || LocalAdditions.importedFileOf(urn) != null);
        }
        // The title is still being read: the apply that follows it decides.
        if (base == null || retimed == null) return;
        // A speed SoundCloud set itself is not reset: only a slowdown made here is undone.
        if (!slowed && !slowedHere) return;
        float factor = slowed ? speed() : 1f;
        try {
            Object exo = exoPlayer(base);
            if (exo == null) return;
            if (setParameters == null) resolve(exo.getClass().getClassLoader());
            // The pitch follows the speed: slower and lower, as a record played slower.
            Object wanted = parameters.newInstance(factor, factor);
            if (getParameters != null && wanted.equals(getParameters.invoke(exo))) {
                slowedHere = slowed;
                return;
            }
            setParameters.invoke(exo, wanted);
            slowedHere = slowed;
            Logger.printInfo(() -> "Slowed playback: " + (again ? "the speed was lost, set again: " : "") + (slowed ? "slowed to " : "normal speed, ") + factor + " for " + urn
                    + (Boolean.TRUE.equals(retimed) ? " (its title says it is at another speed already)" : ""));
        } catch (Throwable ex) {
            // A repeat runs every few seconds: its failure is only logged, the first apply has shown it already.
            if (again) Logger.printInfo(() -> "Slowed playback: could not set the speed again: " + ex);
            else Logger.printException(() -> "Slowed playback: could not set the speed", ex);
        }
    }

    /**
     * SoundCloud's wrapper keeps the Media3 player in a lazy value behind a final method of {@code BaseExoPlayer} without
     * parameters, found by the type it returns. The HLS and file wrappers have a method of the same shape that builds a
     * new player at every call: speed set on that one changed nothing and left a player behind, so only the base class
     * is searched, and only for a method with a body.
     */
    private static Object exoPlayer(Object base) throws Exception {
        Method method = playerOf.get(base.getClass());
        if (method == null) {
            Class<?> playerType = Class.forName("androidx.media3.common.Player", false, base.getClass().getClassLoader());
            Class<?> owner = base.getClass();
            while (owner != null && !owner.getName().equals("com.soundcloud.android.exoplayer.BaseExoPlayer")) {
                owner = owner.getSuperclass();
            }
            if (owner == null) throw new NoSuchMethodException("BaseExoPlayer above " + base.getClass().getName());
            for (Method candidate : owner.getDeclaredMethods()) {
                int modifiers = candidate.getModifiers();
                if (candidate.getParameterTypes().length == 0 && playerType.isAssignableFrom(candidate.getReturnType())
                        && java.lang.reflect.Modifier.isPublic(modifiers) && !java.lang.reflect.Modifier.isAbstract(modifiers)) {
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
        for (Method method : playerType.getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() == parametersType) getParameters = method;
        }
        parameters = parametersType.getConstructor(float.class, float.class);
        setParameters = found;
    }
}
