package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/**
 * How strong the reverb or the bass boost of a version of a track is: off, weak, medium, strong. The numbers are the
 * ones Android's effects take. Used by {@link TrackVersions} and the software effects of the player ({@code player/dsp}).
 */
public enum VersionStrength {
    OFF("off", "Выкл", "Off", 0, 0, 0),
    LOW("low", "Слабо", "Weak", 400, 1100, -1800),
    MID("mid", "Средне", "Medium", 700, 2200, -1100),
    HIGH("high", "Сильно", "Strong", 1000, 3800, -600);

    /** What is stored in the setting. */
    public final String key;
    private final String russian, english;
    /** The bass boost strength in permille (0..1000). */
    public final int bassPermille;
    /** How long the reverb tail lasts, in milliseconds. */
    public final int reverbDecayMillis;
    /** The level of the reverb against the dry sound, in millibels (0 is as loud as the sound itself). */
    public final int reverbLevelMillibels;

    VersionStrength(String key, String russian, String english, int bassPermille, int reverbDecayMillis,
                    int reverbLevelMillibels) {
        this.key = key;
        this.russian = russian;
        this.english = english;
        this.bassPermille = bassPermille;
        this.reverbDecayMillis = reverbDecayMillis;
        this.reverbLevelMillibels = reverbLevelMillibels;
    }

    public String label() {
        return text(russian, english);
    }

    public boolean isOn() {
        return this != OFF;
    }

    /** The gain of the bass shelf in decibels: 4, 7 and 10 for the three strengths. */
    public float bassShelfDb() {
        return this == OFF ? 0f : this == LOW ? 4f : this == MID ? 7f : 10f;
    }

    /** The strength stored under the key; off for a key that is not known. */
    public static VersionStrength of(String key) {
        for (VersionStrength strength : values()) if (strength.key.equals(key)) return strength;
        return OFF;
    }
}
