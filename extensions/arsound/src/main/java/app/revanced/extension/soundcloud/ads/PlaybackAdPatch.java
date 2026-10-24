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

    /**
     * Injection point. SoundCloud is about to insert a fetched ad before a track of the play queue.
     *
     * @param track The track the ad would play before.
     * @return The track alone, so it replaces itself and no ad is inserted; null to insert the ad.
     */
    public static java.util.List<Object> trackWithoutAds(Object track) {
        if (!Settings.isBlockPlaybackAdsEnabled()) return null;
        Logger.printDebug(() -> "Prevented an ad from being inserted into the play queue");
        return java.util.Collections.singletonList(track);
    }

    /** Guard for the ad feature flag and banner conditions, which are checked often, so no logging. */
    public static boolean isAdsBlocked() {
        return Settings.isBlockPlaybackAdsEnabled();
    }
}
