package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Writes to the playback log what decides whether the music is heard, so a report of "the player runs, the
 * notification moves, there is no sound" can be told apart afterwards: the volume the app puts on its player
 * ({@link PlayerVolume}: the smooth transition and the sleep timer), the phone's media volume and mute, where the sound
 * goes (speaker, Bluetooth) and what the system says about the app's players (state, muted).
 * <p>
 * Two kinds of lines. A short one when the player's volume goes silent or comes back. A full snapshot of everything
 * once a minute while a track plays, and every few seconds right after a start or a resume, because "the sound is there
 * for a few seconds and then gone" needs a closer look at those seconds. Nothing here changes playback: every call is
 * wrapped, and a value the phone does not give is written as {@code n/a}.
 */
final class AudioDiagnostics {
    /** Below this the player is taken as silent: the square of a fade is already inaudible there. */
    static final float SILENT_BELOW = 0.02f;
    private static final long QUIET_EVERY_MS = 60_000;
    private static final long CLOSE_EVERY_MS = 5_000;
    /** How long after a start or a resume the snapshots come every {@link #CLOSE_EVERY_MS}. */
    private static final long CLOSE_FOR_MS = 20_000;
    /** A pause of the position reports longer than this means the player was paused and resumes now. */
    private static final long RESUME_GAP_MS = 4_000;

    private static volatile boolean silent;
    private static volatile long nextAt;
    private static volatile long closeUntil;
    private static volatile long lastProgressAt;

    private AudioDiagnostics() {
    }

    /** Whether a player volume counts as silence. */
    static boolean isSilent(float volume) {
        return volume < SILENT_BELOW;
    }

    /** The volume put on the player changed ({@link PlayerVolume#set}). Writes only when it crosses the silence. */
    static void onVolume(float applied, float crossfade, float sleep) {
        try {
            boolean now = isSilent(applied);
            if (now == silent) return;
            silent = now;
            Logger.printInfo(() -> "Audio: the player volume " + (now ? "went silent" : "is back") + ": put " + f(applied)
                    + " (smooth transition " + f(crossfade) + ", sleep timer " + f(sleep) + ")");
        } catch (Throwable ex) {
            Logger.printException(() -> "Audio diagnostics: volume line failed", ex);
        }
    }

    /** An item starts on the player: the next snapshots come close together. */
    static void onPlay() {
        closeFor(SystemClock.uptimeMillis());
    }

    /** A position report of the player, about twice a second while it plays. */
    static void onProgress(long position, long duration) {
        try {
            long now = SystemClock.uptimeMillis();
            long before = lastProgressAt;
            lastProgressAt = now;
            if (before != 0 && now - before > RESUME_GAP_MS) closeFor(now);
            if (now < nextAt) return;
            nextAt = now + (now < closeUntil ? CLOSE_EVERY_MS : QUIET_EVERY_MS);
            String line = snapshot(position, duration);
            Logger.printInfo(() -> line);
        } catch (Throwable ex) {
            Logger.printException(() -> "Audio diagnostics: snapshot failed", ex);
        }
    }

    private static void closeFor(long now) {
        closeUntil = now + CLOSE_FOR_MS;
        nextAt = 0;
    }

    private static String snapshot(long position, long duration) {
        StringBuilder out = new StringBuilder("Audio: at ").append(position / 1000).append('/').append(duration / 1000)
                .append(" s; player volume put ").append(f(PlayerVolume.applied()))
                .append(" (smooth transition ").append(f(PlayerVolume.level(PlayerVolume.Source.CROSSFADE)))
                .append(", sleep timer ").append(f(PlayerVolume.level(PlayerVolume.Source.SLEEP)))
                .append("), the player says ").append(playerVolume());
        Context context = Utils.getContext();
        AudioManager audio = context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return out.append("; no audio service").toString();
        try {
            out.append("; phone: media volume ").append(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).append('/')
                    .append(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .append(audio.isStreamMute(AudioManager.STREAM_MUSIC) ? " MUTED" : "")
                    .append(", mode ").append(audio.getMode())
                    .append(", someone plays ").append(audio.isMusicActive());
        } catch (Throwable ex) {
            out.append("; phone: n/a (").append(ex.getClass().getSimpleName()).append(')');
        }
        out.append("; goes to ").append(route(audio));
        out.append("; system's players ").append(players(audio));
        return out.toString();
    }

    private static String playerVolume() {
        float volume = PlayerVolume.actual();
        return Float.isNaN(volume) ? "n/a" : f(volume);
    }

    /** The outputs the system would use for music. */
    private static String route(AudioManager audio) {
        try {
            if (Build.VERSION.SDK_INT < 33) return "n/a";
            AudioAttributes music = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            List<AudioDeviceInfo> devices = audio.getAudioDevicesForAttributes(music);
            StringBuilder out = new StringBuilder("[");
            for (AudioDeviceInfo device : devices) {
                if (out.length() > 1) out.append(", ");
                out.append(deviceName(device));
            }
            return out.append(']').toString();
        } catch (Throwable ex) {
            return "n/a (" + ex.getClass().getSimpleName() + ")";
        }
    }

    /** What the system keeps about the players that are active: only the app's own are given in full. */
    private static String players(AudioManager audio) {
        try {
            List<AudioPlaybackConfiguration> configs = audio.getActivePlaybackConfigurations();
            StringBuilder out = new StringBuilder().append(configs.size()).append(" [");
            for (AudioPlaybackConfiguration config : configs) {
                if (out.charAt(out.length() - 1) != '[') out.append("; ");
                AudioAttributes attributes = config.getAudioAttributes();
                out.append("usage ").append(attributes.getUsage());
                String state = hidden(config, "getPlayerState");
                String muted = hidden(config, "isMuted");
                String mutedBy = hidden(config, "getMutedBy");
                out.append(", state ").append(state).append(", muted ").append(muted).append(", muted by ").append(mutedBy);
                if (Build.VERSION.SDK_INT >= 34) {
                    AudioDeviceInfo device = config.getAudioDeviceInfo();
                    out.append(", device ").append(device == null ? "none" : deviceName(device));
                }
            }
            return out.append(']').toString();
        } catch (Throwable ex) {
            return "n/a (" + ex.getClass().getSimpleName() + ")";
        }
    }

    /** A method the system hides from apps: the answer, or {@code n/a} when the phone does not give it. */
    private static String hidden(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return String.valueOf(method.invoke(target));
        } catch (Throwable ex) {
            return "n/a";
        }
    }

    /**
     * The kind of the output only. The product name is left out on purpose: a headset is often named after its owner
     * ("Ivan's AirPods"), and the log is shared when a problem is reported.
     */
    private static String deviceName(AudioDeviceInfo device) {
        return typeName(device.getType());
    }

    /** The common kinds of output by name; the rest by number. */
    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired-headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired-headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bluetooth-call";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bluetooth-music";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "usb";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "usb-headset";
            case AudioDeviceInfo.TYPE_HDMI: return "hdmi";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "bluetooth-le-headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "bluetooth-le-speaker";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST: return "bluetooth-le-broadcast";
            default: return "type" + type;
        }
    }

    private static String f(float value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
