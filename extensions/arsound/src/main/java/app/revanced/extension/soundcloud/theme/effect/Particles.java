package app.revanced.extension.soundcloud.theme.effect;

/** How many particles a scene has. */
final class Particles {
    private Particles() {
    }

    /** The design's count scaled by the density the user chose, never fewer than one. */
    static int count(int design, float density) {
        return Math.max(1, Math.round(design * density));
    }
}
