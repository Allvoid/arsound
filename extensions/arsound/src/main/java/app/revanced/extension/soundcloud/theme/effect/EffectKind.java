package app.revanced.extension.soundcloud.theme.effect;

import app.revanced.extension.soundcloud.player.CurrentPlayState;

/** The animated backgrounds there are, each made for the theme whose id it carries. */
public enum EffectKind {
    /** Sparks rising from a glow at the bottom. */
    SCARLET("scarlet") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new SparksScene(brush, density);
        }
    },
    /** A night sky with meteors. */
    COBALT("cobalt") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new StarsScene(brush, density);
        }
    },
    /** Fireflies. */
    MINT("mint") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new FirefliesScene(brush, density);
        }
    },
    /** Falling petals. */
    SAKURA("sakura") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new PetalsScene(brush, density);
        }
    },
    /** A scanner line with rising squares. */
    LIME("lime") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new SquaresScene(brush, density);
        }
    },
    /** Snowfall under a frozen top edge. */
    WINTER("winter") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new SnowScene(brush, density);
        }
    },
    /** Rain, rings on the water and a rare forked bolt. */
    STORM("storm") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new StormScene(brush, density);
        }
    },
    /** A record that turns while the music plays. */
    VINYL("vinyl") {
        @Override
        public EffectScene scene(EffectBrush brush, float density) {
            return new VinylScene(brush, density, CurrentPlayState::isPlaying);
        }
    };

    /** The effect id, the id of the theme it was made for. */
    public final String id;

    EffectKind(String id) {
        this.id = id;
    }

    /** The scene of this effect, with the amount of particles the user chose (1 as in the design). */
    public abstract EffectScene scene(EffectBrush brush, float density);

    /** The effect of this id, or null for a theme without one. */
    public static EffectKind fromId(String id) {
        if (id == null) return null;
        for (EffectKind kind : values()) if (kind.id.equals(id)) return kind;
        return null;
    }
}
