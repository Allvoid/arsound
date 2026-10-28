package app.revanced.extension.soundcloud.player;

/**
 * What the band levels of the equalizer came from, kept as text in the settings: {@code "p:<id>"} for a built-in
 * preset, {@code "u:<name>"} for one of the user's presets, and an empty text for the user's own levels. The settings
 * screen reads the same text, so the forms must not change.
 */
final class EqualizerChoice {
    static final String BUILT_IN_PREFIX = "p:";
    static final String USER_PREFIX = "u:";

    private EqualizerChoice() {
    }

    /** The choice for one of the user's presets. */
    static String userPreset(String name) {
        return USER_PREFIX + name;
    }

    /** Whether the choice is this user preset. */
    static boolean isUserPreset(String choice, String name) {
        return userPreset(name).equals(choice);
    }
}
