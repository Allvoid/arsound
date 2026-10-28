package app.revanced.extension.soundcloud.theme.effect;

/** The timing curves of the effects, after CSS keyframe animations. Pure arithmetic, so it also runs in the JVM tests. */
public final class Keyframes {
    private Keyframes() {
    }

    /** Progress 0..1 of a looping animation with a CSS-style delay (negative: already running). -1 before it starts. */
    public static float progress(float time, float duration, float delay) {
        float local = time - delay;
        if (local < 0) return -1;
        return (local % duration) / duration;
    }

    /** CSS ease-in-out, close enough for slow loops. */
    public static float ease(float value) {
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * value));
    }

    /** A loop 0 → 1 → 0 with ease-in-out on both halves, as keyframes 0%, 50%, 100%. */
    public static float pulse(float progress) {
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * progress));
    }
}
