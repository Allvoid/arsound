package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/**
 * The speed of a version of a track. The pitch follows the speed, as on a record played faster or slower: slowed is
 * slower and lower, sped up is faster and higher. Used by {@link TrackVersions}.
 */
public enum VersionSpeed {
    NORMAL("off", "Обычная", "Normal") {
        @Override
        public float factor(int slowedPercent, int speedUpPercent) {
            return 1f;
        }
    },
    SLOWED("slowed", "Slowed", "Slowed") {
        @Override
        public float factor(int slowedPercent, int speedUpPercent) {
            return Math.max(50, Math.min(99, slowedPercent)) / 100f;
        }
    },
    SPEED_UP("speedup", "Speed up", "Speed up") {
        @Override
        public float factor(int slowedPercent, int speedUpPercent) {
            return Math.max(101, Math.min(200, speedUpPercent)) / 100f;
        }
    };

    /** What is stored in the setting. */
    public final String key;
    private final String russian, english;

    VersionSpeed(String key, String russian, String english) {
        this.key = key;
        this.russian = russian;
        this.english = english;
    }

    /** The name in the app language. */
    public String label() {
        return text(russian, english);
    }

    /** The speed (and pitch) as a factor of the track's own, for the percents the user chose for slowed and sped up. */
    public abstract float factor(int slowedPercent, int speedUpPercent);

    /** The speed stored under the key; normal for a key that is not known. */
    public static VersionSpeed of(String key) {
        for (VersionSpeed speed : values()) if (speed.key.equals(key)) return speed;
        return NORMAL;
    }
}
