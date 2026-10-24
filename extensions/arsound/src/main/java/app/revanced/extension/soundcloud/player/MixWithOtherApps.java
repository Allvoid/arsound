package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

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
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** SoundCloud's focus request (androidx AudioFocusRequestCompat) of the player. */
    private static volatile WeakReference<Object> focusRequest = new WeakReference<>(null);
    /** True while the playback started without the focus. */
    private static volatile boolean withoutFocus;
    /** True between the pause for a call and the end of the call. Main thread only. */
    private static boolean pausedForCall;
    private static boolean watching;

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
            if (!Settings.isMixWithOtherAppsEnabled()) {
                withoutFocus = false;
                return false;
            }
            Context context = Utils.getContext();
            AudioManager audio = context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                withoutFocus = false;
                return false;
            }
            // During a call the request is refused, and the player does not start over the call, as usual.
            if (isCall(audio.getMode())) {
                withoutFocus = false;
                return false;
            }
            // The focus may still be held from a playback started before the setting was turned on.
            abandon(audio, request);
            withoutFocus = true;
            main.post(MixWithOtherApps::watchCalls);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: could not skip the audio focus", ex);
            withoutFocus = false;
            return false;
        }
    }

    /** Called when the setting changes. Turning it on lets go of the focus at once, not at the next track. */
    public static void onSettingChanged(boolean enabled) {
        if (!enabled) return;
        Object request = focusRequest.get();
        Context context = Utils.getContext();
        if (request == null || context == null) return;
        try {
            abandon((AudioManager) context.getSystemService(Context.AUDIO_SERVICE), request);
            withoutFocus = true;
            main.post(MixWithOtherApps::watchCalls);
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: could not release the audio focus", ex);
        }
    }

    private static void abandon(AudioManager audio, Object request) throws IllegalAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audio == null) return;
        Object platformRequest = fieldOfType(request, AudioFocusRequest.class);
        if (platformRequest != null) audio.abandonAudioFocusRequest((AudioFocusRequest) platformRequest);
    }

    /** The modes of a ringing phone, a phone call and a call in a messenger. */
    private static boolean isCall(int mode) {
        return mode > AudioManager.MODE_NORMAL;
    }

    /** Starts watching for calls once; the watchers stay for the life of the process and cost nothing in between. */
    private static void watchCalls() {
        if (watching) return;
        Context context = Utils.getContext();
        if (context == null) return;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        watching = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.addOnModeChangedListener(context.getMainExecutor(), MixWithOtherApps::onModeChanged);
                return;
            }
            // Before Android 12 there is no mode listener. The phone call state needs no permission there, and a
            // messenger call starts a voice playback, which the playback callback reports.
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony != null) {
                telephony.listen(new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        onModeChanged(audio.getMode());
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        onModeChanged(audio.getMode());
                    }
                }, main);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Mix with other apps: could not watch for calls", ex);
        }
    }

    /** How often the mode is asked again while the music waits for a call to end. */
    private static final long CALL_RECHECK_MS = 1_000;

    private static final Runnable RECHECK = () -> {
        Context context = Utils.getContext();
        AudioManager audio = context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) onModeChanged(audio.getMode());
    };

    /** Main thread. Pauses for a call and resumes after it, through SoundCloud's own focus listener. */
    private static void onModeChanged(int mode) {
        boolean call = isCall(mode);
        if (call != pausedForCall && (!call || withoutFocus)) {
            AudioManager.OnAudioFocusChangeListener listener = focusListener();
            if (listener != null) {
                pausedForCall = call;
                Logger.printDebug(() -> "Mix with other apps: " + (call ? "call started, mode " + mode : "call ended"));
                try {
                    // A call takes the focus for a while: SoundCloud pauses if it plays and remembers to resume.
                    listener.onAudioFocusChange(call ? AudioManager.AUDIOFOCUS_LOSS_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Mix with other apps: the focus listener failed", ex);
                }
            }
        }
        // Before Android 12 the phone call state turns idle a moment before the mode does, and nothing reports the
        // end of a messenger call, so the music stayed paused after the call. While it waits for a call to end, the
        // mode is asked again every second.
        main.removeCallbacks(RECHECK);
        if (pausedForCall) main.postDelayed(RECHECK, CALL_RECHECK_MS);
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

    /** Field names of the androidx class are shrunk, so a field is found by the type of its value. */
    private static Object fieldOfType(Object owner, Class<?> type) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(owner);
            if (type.isInstance(value)) return value;
        }
        return null;
    }
}
