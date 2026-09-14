package app.revanced.extension.soundcloud.analytics;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

@SuppressWarnings("unused")
public final class DisableTelemetryPatch {
    /**
     * Injection point. Called with the backend name before SoundCloud creates its tracking API.
     *
     * @return The original backend name if telemetry is enabled,
     * otherwise an empty name, which makes SoundCloud skip creating the tracker.
     */
    public static String getTrackingBackend(String backend) {
        if (Settings.isTelemetryEnabled()) return backend;

        Logger.printDebug(() -> "Blocking tracking backend: " + backend);
        return "";
    }
}
