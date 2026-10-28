/**
 * Playback ads: keeps SoundCloud's audio ads out of the player.
 *
 * <p>User view: with "Block playback ads" on (default), no preroll or midroll ad is requested, so playback never
 * pauses for an advertisement or sits in a loading state.
 *
 * <p>Entry points (called by the Kotlin patch {@code ads/PlaybackAdsPatch.kt}; the descriptor is
 * {@code Lapp/revanced/extension/soundcloud/ads/PlaybackAdPatch;}):
 * <ul>
 * <li>{@code PlaybackAdPatch.blockAdRequest()} - the player-ad controller leaves before it fetches an ad.</li>
 * <li>{@code PlaybackAdPatch.trackWithoutAds(Object)} - the play queue gets the track alone instead of "ad + track".</li>
 * <li>{@code PlaybackAdPatch.isAdsBlocked()} - guard for the ad feature flag and banner conditions (called often, so it
 * does not log).</li>
 * </ul>
 *
 * <p>Settings: {@code block_playback_ads} ({@code Settings.isBlockPlaybackAdsEnabled()}, default on), shown on the
 * screen {@code AdsScreen} of the {@code settings} package.
 *
 * <p>Gotchas: the three methods above are named in the patch; do not rename them or change their signatures. The class
 * has only static methods and no state.
 */
package app.revanced.extension.soundcloud.ads;
