package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Watches for calls while the music plays without the audio focus, and gives SoundCloud's own focus listener the same
 * "lost for a while" and "back" events a call would give it: the music pauses during the call and resumes after it.
 * Everything here runs on the main thread.
 */
final class CallWatcher {
    /** How often the mode is asked again while the music waits for a call to end. */
    private static final long CALL_RECHECK_MS = 1_000;

    private final Handler main;
    /** SoundCloud's focus listener of the player; null when the player is gone. */
    private final Supplier<AudioManager.OnAudioFocusChangeListener> focusListener;
    /** Whether the playback started without the focus; only then does a call pause the music. */
    private final BooleanSupplier withoutFocus;

    /** True between the pause for a call and the end of the call. */
    private boolean pausedForCall;
    private boolean watching;

    private final Runnable recheck = () -> {
        AudioManager audio = audio();
        if (audio != null) onModeChanged(audio.getMode());
    };

    CallWatcher(Handler main, Supplier<AudioManager.OnAudioFocusChangeListener> focusListener,
                BooleanSupplier withoutFocus) {
        this.main = main;
        this.focusListener = focusListener;
        this.withoutFocus = withoutFocus;
    }

    private static AudioManager audio() {
        Context context = Utils.getContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** The modes of a ringing phone, a phone call and a call in a messenger. */
    static boolean isCall(int mode) {
        return mode > AudioManager.MODE_NORMAL;
    }

    /** Starts watching for calls once; the watchers stay for the life of the process and cost nothing in between. */
    void start() {
        if (watching) return;
        Context context = Utils.getContext();
        if (context == null) return;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        watching = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.addOnModeChangedListener(context.getMainExecutor(), this::onModeChanged);
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

    /** Pauses for a call and resumes after it, through SoundCloud's own focus listener. */
    private void onModeChanged(int mode) {
        boolean call = isCall(mode);
        if (call != pausedForCall && (!call || withoutFocus.getAsBoolean())) {
            AudioManager.OnAudioFocusChangeListener listener = focusListener.get();
            if (listener != null) {
                pausedForCall = call;
                Logger.printDebug(() -> "Mix with other apps: " + (call ? "call started, mode " + mode : "call ended"));
                try {
                    // A call takes the focus for a while: SoundCloud pauses if it plays and remembers to resume.
                    listener.onAudioFocusChange(call ? AudioManager.AUDIOFOCUS_LOSS_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Mix with other apps: the focus listener failed", ex);
                }
            } else if (!call) {
                // The player is gone: nothing to resume, and the mode is not asked for again.
                pausedForCall = false;
            }
        }
        // Before Android 12 the phone call state turns idle a moment before the mode does, and nothing reports the
        // end of a messenger call, so the music stayed paused after the call. While it waits for a call to end, the
        // mode is asked again every second.
        main.removeCallbacks(recheck);
        if (pausedForCall) main.postDelayed(recheck, CALL_RECHECK_MS);
    }
}
