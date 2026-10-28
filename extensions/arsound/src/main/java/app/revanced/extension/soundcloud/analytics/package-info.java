/**
 * Telemetry: stops SoundCloud from creating its tracking API.
 *
 * <p>User view: with usage statistics off (the default) SoundCloud's analytics backend is never started.
 *
 * <p>Entry point (called by {@code analytics/DisableTelemetryPatch.kt}):
 * {@code DisableTelemetryPatch.getTrackingBackend(String)}. It gets the backend name before the tracker is created and
 * returns it unchanged when telemetry is allowed, or an empty string, which makes SoundCloud skip the tracker.
 *
 * <p>Settings: {@code telemetry_enabled} ({@code Settings.isTelemetryEnabled()}, default off), on the privacy screen
 * {@code PrivacyScreen} of the {@code settings} package. Reports of other bundled SDKs are cut elsewhere, by
 * {@code power.PowerSavingPatch.isBackgroundReportBlocked}.
 *
 * <p>Gotchas: do not rename the method; the patch calls it by name. The only class here is
 * {@code DisableTelemetryPatch}.
 */
package app.revanced.extension.soundcloud.analytics;
