package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * An equalizer on the player's sound: Android's own effect, attached to every audio session
 * the player creates. The chosen preset or band levels are applied at once to the sound that plays.
 */
@SuppressWarnings("unused")
public final class AudioEqualizer {
    public static final String ENABLED = "equalizer_enabled";
    /** The chosen preset of the device, or {@link #CUSTOM} for the user's own band levels. */
    public static final String PRESET = "equalizer_preset";
    /** Band levels in millibels, comma separated, lowest band first. */
    public static final String LEVELS = "equalizer_levels";
    public static final long CUSTOM = -1;

    private static final List<Equalizer> active = new ArrayList<>();

    private AudioEqualizer() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, false);
    }

    /**
     * Injection point: the player got a new audio session.
     *
     * @param sessionId The session id, 0 if none was given.
     */
    public static void onAudioSession(int sessionId) {
        if (sessionId <= 0) return;
        try {
            Equalizer equalizer = new Equalizer(0, sessionId);
            synchronized (active) {
                active.add(equalizer);
            }
            apply(equalizer);
            Logger.printInfo(() -> "Equalizer attached to audio session " + sessionId + ", enabled " + isEnabled());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not attach the equalizer", ex);
        }
    }

    /** Applies the settings to the sound that plays now. */
    public static void applyAll() {
        synchronized (active) {
            for (Equalizer equalizer : active) apply(equalizer);
        }
    }

    private static void apply(Equalizer equalizer) {
        try {
            boolean enabled = isEnabled();
            if (enabled) {
                long preset = Settings.getLong(PRESET, CUSTOM);
                if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                    equalizer.usePreset((short) preset);
                } else {
                    short[] levels = levels(equalizer.getNumberOfBands());
                    for (short band = 0; band < levels.length; band++) equalizer.setBandLevel(band, levels[band]);
                }
            }
            equalizer.setEnabled(enabled);
        } catch (Throwable ex) {
            // The session ended: the effect is dropped.
            synchronized (active) {
                active.remove(equalizer);
            }
            equalizer.release();
        }
    }

    /** The user's band levels; missing bands are flat. */
    public static short[] levels(int bands) {
        short[] levels = new short[bands];
        String saved = Settings.getString(LEVELS, "");
        String[] parts = saved.isEmpty() ? new String[0] : saved.split(",");
        for (int i = 0; i < bands && i < parts.length; i++) {
            try {
                levels[i] = Short.parseShort(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return levels;
    }

    public static void setLevel(int band, short level, int bands) {
        short[] levels = levels(bands);
        levels[band] = level;
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < levels.length; i++) value.append(i == 0 ? "" : ",").append(levels[i]);
        Settings.putString(LEVELS, value.toString());
        Settings.putLong(PRESET, CUSTOM);
        applyAll();
    }

    /** What the settings screen shows: bands, their ranges and the device presets. */
    public static final class Info {
        public int[] centerFrequenciesHz = new int[0];
        public short minLevel;
        public short maxLevel;
        public String[] presets = new String[0];
        /** Band levels of each preset, to show them on the sliders. */
        public short[][] presetLevels = new short[0][];
    }

    /** Reads the equalizer of this device on a session of its own. */
    public static Info readInfo() {
        Info info = new Info();
        Equalizer equalizer = null;
        try {
            Context context = Utils.getContext();
            int session = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).generateAudioSessionId();
            equalizer = new Equalizer(0, session);
            short bands = equalizer.getNumberOfBands();
            info.centerFrequenciesHz = new int[bands];
            for (short band = 0; band < bands; band++) info.centerFrequenciesHz[band] = equalizer.getCenterFreq(band) / 1000;
            short[] range = equalizer.getBandLevelRange();
            info.minLevel = range[0];
            info.maxLevel = range[1];
            short presets = equalizer.getNumberOfPresets();
            info.presets = new String[presets];
            info.presetLevels = new short[presets][];
            for (short preset = 0; preset < presets; preset++) {
                info.presets[preset] = equalizer.getPresetName(preset);
                equalizer.usePreset(preset);
                info.presetLevels[preset] = new short[bands];
                for (short band = 0; band < bands; band++) info.presetLevels[preset][band] = equalizer.getBandLevel(band);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the equalizer", ex);
        } finally {
            if (equalizer != null) equalizer.release();
        }
        return info;
    }
}
