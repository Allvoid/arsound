/**
 * Power saving: less background work that wakes the radio for no visible benefit.
 *
 * <p>User view: with "Power saving" on (default) the unread-messages badge is polled every 5 minutes instead of every
 * 30 seconds, the background reports of bundled SDKs are not sent, and playing a local file does not keep Wi-Fi awake.
 *
 * <p>Entry points in {@code PowerSavingPatch} (called by {@code power/PowerSavingPatch.kt}):
 * <ul>
 * <li>{@code inboxPollSeconds(long)} - returns the longer polling interval.</li>
 * <li>{@code onPlaybackItem(Object player, Object item)} - remembers by reflection whether the item plays from a file
 * ({@code shared.PlaybackStreams.isFileStream}).</li>
 * <li>{@code keepWifiAwake(boolean)} - false while a file plays and the setting is on; the CPU wake lock is kept.</li>
 * </ul>
 * {@code isBackgroundReportBlocked(host, path)} is not called by a patch: {@code network.NetworkPatch} uses it on every
 * OkHttp request to drop reports to the feature-flag analytics and marketing hosts.
 *
 * <p>Settings: {@code power_saving} ({@code Settings.isPowerSavingEnabled()}, default on).
 *
 * <p>Gotchas: {@code onPlaybackItem} finds SoundCloud's obfuscated player method by its shape (a one-argument method
 * named {@code k} that returns a {@code Stream}); a SoundCloud update can break that, and then it falls back to "not a
 * file". The patched methods must keep their names.
 */
package app.revanced.extension.soundcloud.power;
