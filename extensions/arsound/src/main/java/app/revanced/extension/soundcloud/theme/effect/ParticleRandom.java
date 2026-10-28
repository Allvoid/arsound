package app.revanced.extension.soundcloud.theme.effect;

/**
 * The random sequence of the design's script (a Lehmer generator, multiplier 16807, seed 7), so that the particles
 * start where the design has them. Every effect draws its own sequence from the same seed.
 */
public final class ParticleRandom {
    private long seed = 7;

    /** The next number in [0, 1]. */
    public float next() {
        seed = (seed * 16807) % 2147483647;
        return (seed - 1) / 2147483646f;
    }
}
