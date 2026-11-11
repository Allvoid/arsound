package app.revanced.extension.soundcloud.player;

import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The versions of every track the player makes on the fly: the speed with the pitch following it (slowed, sped up), a
 * reverb and a bass boost, each with its strength. They combine: "slowed + reverb" plays slowed with the reverb on.
 * What is chosen stays until the user changes it. The speed is set by {@link SlowedPlayback}, the reverb and the bass
 * in software, in the audio chain of the player, next to the equalizer ({@link SoftEqualizerProcessor}).
 */
public final class TrackVersions {
    public static final String SPEED = "fx_speed";
    public static final String REVERB = "fx_reverb";
    public static final String BASS = "fx_bass";
    /** How slow "slowed" is and how fast "sped up" is, in percent of the track's own speed. */
    public static final String SLOWED_PERCENT = Settings.SLOWED_PERCENT;
    public static final String SPEED_UP_PERCENT = "fx_speedup_percent";
    public static final int DEFAULT_SLOWED_PERCENT = 85;
    public static final int DEFAULT_SPEED_UP_PERCENT = 125;
    public static final long[] SLOWED_PERCENTS = {90, 85, 80, 75, 70, 65};
    public static final long[] SPEED_UP_PERCENTS = {110, 115, 120, 125, 130, 140};

    private TrackVersions() {
    }

    /**
     * The chosen speed. Before the user chose a version, the old setting "play everything slowed" decides, so what was
     * switched on before stays on.
     */
    public static VersionSpeed speed() {
        String saved = Settings.getString(SPEED, "");
        if (saved.isEmpty()) return BoolSettings.SLOWED_PLAYBACK.get() ? VersionSpeed.SLOWED : VersionSpeed.NORMAL;
        return VersionSpeed.of(saved);
    }

    public static void setSpeed(VersionSpeed speed) {
        Settings.putString(SPEED, speed.key);
        // The old setting follows: it is what older code and the migration above read.
        BoolSettings.SLOWED_PLAYBACK.set(speed == VersionSpeed.SLOWED);
    }

    public static VersionStrength reverb() {
        return VersionStrength.of(Settings.getString(REVERB, ""));
    }

    public static void setReverb(VersionStrength strength) {
        Settings.putString(REVERB, strength.key);
    }

    public static VersionStrength bass() {
        return VersionStrength.of(Settings.getString(BASS, ""));
    }

    public static void setBass(VersionStrength strength) {
        Settings.putString(BASS, strength.key);
    }

    /** Whether any version is on: the button in the player is lit. */
    public static boolean anyOn() {
        return speed() != VersionSpeed.NORMAL || reverb().isOn() || bass().isOn();
    }

    /** The speed factor (and pitch factor) of the chosen version, before a title that says it is retimed already is taken into account. */
    public static float speedFactor() {
        return factorOf(speed());
    }

    /** The speed factor (and pitch factor) of a speed, with the percents the user chose for slowed and sped up. */
    public static float factorOf(VersionSpeed speed) {
        int slowed = (int) Settings.getLong(SLOWED_PERCENT, DEFAULT_SLOWED_PERCENT);
        int speedUp = (int) Settings.getLong(SPEED_UP_PERCENT, DEFAULT_SPEED_UP_PERCENT);
        return speed.factor(slowed, speedUp);
    }

    /** A short line with the chosen versions for the toast and the settings summary: "Slowed + Reverb (средне)". */
    public static String summary() {
        StringBuilder line = new StringBuilder();
        if (speed() != VersionSpeed.NORMAL) line.append(speed().label());
        if (reverb().isOn()) append(line, "Reverb", reverb());
        if (bass().isOn()) append(line, "Bass boost", bass());
        return line.toString();
    }

    private static void append(StringBuilder line, String name, VersionStrength strength) {
        if (line.length() > 0) line.append(" + ");
        line.append(name).append(" (").append(strength.label().toLowerCase(java.util.Locale.ROOT)).append(')');
    }
}
