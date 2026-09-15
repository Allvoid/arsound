package app.revanced.extension.soundcloud.ads;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/** Entry points injected into SoundCloud's player-ad controller. */
@SuppressWarnings("unused")
public final class PlaybackAdPatch {
    private PlaybackAdPatch() {
    }

    /**
     * Returning true makes the controller leave before it starts a preroll or midroll fetch.
     * This is deliberately before the network operation and queue mutation, avoiding an empty
     * advertisement item or a playback pause.
     */
    public static boolean blockAdRequest() {
        boolean blocked = Settings.isBlockPlaybackAdsEnabled();
        if (blocked) Logger.printDebug(() -> "Prevented playback advertisement request");
        return blocked;
    }
}
