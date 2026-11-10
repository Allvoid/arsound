package app.revanced.extension.soundcloud.recommendations;

/**
 * The numbers behind the moving lines of the "My Wave" tab (taken from the mockup, {@code drawWaves}): the level
 * {@code L} from 0 (quiet) to 1 (loud) and what it does to the lines. No Android here, so tests can run it.
 */
final class WaveLevel {
    private WaveLevel() {
    }

    /**
     * What the level heads for. Paused: 0. Playing: the loudness of the music with a faint drift, so a steady tone
     * still breathes; without a loudness ({@code signal < 0}) the player's volume with the mockup's soft shiver.
     *
     * @param signal  The loudness of the music, 0 to 1, or -1 when it is not known.
     * @param volume  The player's volume, 0 to 1, used when the loudness is not known.
     * @param seconds A clock for the drift.
     */
    static float target(boolean playing, float signal, float volume, double seconds) {
        if (!playing) return 0f;
        if (signal < 0f) {
            return clamp(volume * (float) (0.72 + 0.18 * Math.sin(1.7 * seconds) + 0.1 * Math.sin(0.6 * seconds + 1)));
        }
        return clamp((float) (0.12 + 0.82 * signal + 0.04 * Math.sin(1.7 * seconds)));
    }

    /** One step of the level towards its target: faster up than down. */
    static float follow(float level, float target, float seconds) {
        float rate = target > level ? 3.5f : 2f;
        return level + (target - level) * (1f - (float) Math.exp(-seconds * rate));
    }

    /** The size of the swing of the lines, in design units (the strip is 150 high). */
    static float amplitude(float level) {
        return 6f + 30f * level;
    }

    /** How fast the phase runs. */
    static float speed(float level) {
        return 0.4f + 2.6f * level;
    }

    /** How much closer together the waves lie. */
    static float frequency(float level) {
        return 1f + 0.8f * level;
    }

    /** The thickness of the lines, as a multiplier. */
    static float weight(float level) {
        return 0.8f + 1.2f * level;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
