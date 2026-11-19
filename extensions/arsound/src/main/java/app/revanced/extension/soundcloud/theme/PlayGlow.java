package app.revanced.extension.soundcloud.theme;

import app.revanced.extension.soundcloud.settings.BoolSettings;

/**
 * The glow around the play and pause buttons in the colour of the theme (the desktop's "Play button glow"). On by
 * default. Off puts the flat copies of the buttons into the theme's resource table in place of the glowing ones: the
 * same circle with the same size and the same sign, only without the soft light around it. The choice is part of the
 * table's key ({@link FontChoice#key}), so a change builds a new table, which is applied by restarting the app.
 */
public final class PlayGlow {
    /** The drawables of the buttons (the theme patch's {@code parts/playerButtons}); each has a flat copy. */
    static final String[] BUTTONS = {
            "ic_playback_play", "ic_playback_pause", "ic_playback_play_with_state", "ic_playback_pause_with_state"};
    private static final String FLAT_PREFIX = "arsound_flat_";

    private PlayGlow() {
    }

    public static boolean isOn() {
        return BoolSettings.PLAY_GLOW.get();
    }

    /** The name of the flat copy of a button's drawable, as the theme patch ships it. */
    static String flatName(String button) {
        return FLAT_PREFIX + button;
    }

    /** What the resource table depends on: empty with the glow, a mark without it. */
    static String key() {
        return isOn() ? "" : "flat";
    }
}
