package app.revanced.extension.soundcloud.settings;

import java.util.Objects;

/**
 * What the equalizer curve came from: one of the built-in presets, a preset the user saved, or a tuning that was not
 * saved. It is kept as text in {@code AudioEqualizer.CHOICE}: "p:&lt;id&gt;", "u:&lt;name&gt;", or empty for custom.
 */
final class EqualizerChoice {
    private static final String BUILT_IN_PREFIX = "p:";
    private static final String USER_PREFIX = "u:";

    /** The curve was dragged by hand and not saved under a name. */
    static final EqualizerChoice CUSTOM = new EqualizerChoice("");

    private final String stored;

    private EqualizerChoice(String stored) {
        this.stored = stored;
    }

    static EqualizerChoice builtIn(String presetId) {
        return new EqualizerChoice(BUILT_IN_PREFIX + presetId);
    }

    static EqualizerChoice user(String presetName) {
        return new EqualizerChoice(USER_PREFIX + presetName);
    }

    /** The choice kept as {@code stored}; anything that is neither a preset nor a user preset is custom. */
    static EqualizerChoice parse(String stored) {
        if (stored.startsWith(BUILT_IN_PREFIX) || stored.startsWith(USER_PREFIX)) return new EqualizerChoice(stored);
        return CUSTOM;
    }

    boolean isBuiltIn() {
        return stored.startsWith(BUILT_IN_PREFIX);
    }

    boolean isUser() {
        return stored.startsWith(USER_PREFIX);
    }

    /** The id of the built-in preset or the name of the user's preset; empty for custom. */
    String value() {
        return isBuiltIn() || isUser() ? stored.substring(2) : "";
    }

    String toStored() {
        return stored;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EqualizerChoice && ((EqualizerChoice) other).stored.equals(stored);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stored);
    }
}
