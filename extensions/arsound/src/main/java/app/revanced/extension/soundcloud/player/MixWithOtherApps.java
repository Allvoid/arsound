package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Plays along with other apps instead of stopping for them.
 * <p>
 * Android pauses a player through the audio focus: whoever starts playing takes it, and the app that loses it
 * pauses (SoundCloud does it in its focus listener). Since Android 12 the system also fades out and mutes an app
 * that keeps playing after losing the focus, so ignoring the loss is not enough. Here SoundCloud does not take the
 * focus at all, and nothing makes it stop: a video, a voice message, a game or another player play at the same time.
 * <p>
 * Calls are the exception. Without the focus nobody tells SoundCloud about a ringing phone, so the call is watched
 * here, and SoundCloud's own focus listener gets the same "lost for a while" and "back" events a call would give it:
 * it pauses during the call and resumes after it, as usual.
 */
@SuppressWarnings("unused")
public final class MixWithOtherApps {
    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** SoundCloud's focus request (androidx AudioFocusRequestCompat) of the player. */
    private static volatile WeakReference<Object> focusRequest = new WeakReference<>(null);
    /** True while the playback started without the focus. */
    private static volatile boolean withoutFocus;

    private static final CallWatcher CALLS = new CallWatcher(handler, MixWithOtherApps::focusListener,
            () -> withoutFocus);

    private MixWithOtherApps() {
    }

    /**
     * Injection point. The start of SoundCloud's audio focus request, made before every start and resume.
     *
     * @param request The player's focus request.
     * @return True to play without asking for the focus.
     */
    public static boolean skipAudioFocus(Object request) {
        focusRequest = new WeakReference<>(request);
        try {
            if (!Settings.isMixWithOtherAppsEnabled()) return keepFocus();
            AudioManager audio = audio();
            if (audio == null) return keepFocus();
            // During a call the request is refused, and the player does not start over the call, as usual.
            if (CallWatcher.isCall(audio.getMode())) return keepFocus();
            // The focus may still be held from a playback started before the setting was turned on.
            playWithoutFocus(audio, request);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: could not skip the audio focus", ex);
            return keepFocus();
        }
    }

    /** SoundCloud asks for the focus as usual. @return False, the answer of the hook. */
    private static boolean keepFocus() {
        withoutFocus = false;
        return false;
    }

    /** Called when the setting changes. Turning it on lets go of the focus at once, not at the next track. */
    public static void onSettingChanged(boolean enabled) {
        if (!enabled) return;
        Object request = focusRequest.get();
        Context context = Utils.getContext();
        if (request == null || context == null) return;
        try {
            playWithoutFocus((AudioManager) context.getSystemService(Context.AUDIO_SERVICE), request);
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: could not release the audio focus", ex);
        }
    }

    /** Lets go of the focus the player may hold, then plays on without it, with calls watched. */
    private static void playWithoutFocus(AudioManager audio, Object request) throws IllegalAccessException {
        abandon(audio, request);
        withoutFocus = true;
        handler.post(CALLS::start);
    }

    private static AudioManager audio() {
        Context context = Utils.getContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static void abandon(AudioManager audio, Object request) throws IllegalAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audio == null) return;
        Object platformRequest = fieldOfType(request, AudioFocusRequest.class);
        if (platformRequest != null) audio.abandonAudioFocusRequest((AudioFocusRequest) platformRequest);
    }

    private static AudioManager.OnAudioFocusChangeListener focusListener() {
        Object request = focusRequest.get();
        if (request == null) return null;
        try {
            return (AudioManager.OnAudioFocusChangeListener) fieldOfType(request, AudioManager.OnAudioFocusChangeListener.class);
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: no focus listener", ex);
            return null;
        }
    }

    /**
     * Field names of the androidx class are shrunk, so a field is found by the type of its value (not of the field,
     * which {@link Reflect#field} would match).
     */
    private static Object fieldOfType(Object owner, Class<?> type) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (Reflect.isStatic(field)) continue;
            field.setAccessible(true);
            Object value = field.get(owner);
            if (type.isInstance(value)) return value;
        }
        return null;
    }
}
