package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/**
 * Settings of the ReVanced SoundCloud patches, stored in a separate preferences file.
 */
@SuppressWarnings("unused")
public final class Settings {
    private static final String PREFERENCES_NAME = "revanced_soundcloud";

    public static final String TELEMETRY_ENABLED = "telemetry_enabled";

    private static SharedPreferences getPreferences() {
        Context context = Utils.getContext();
        if (context == null) return null;

        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isTelemetryEnabled() {
        SharedPreferences preferences = getPreferences();
        // Telemetry is off by default. If the context is not yet available, keep it off as well.
        return preferences != null && preferences.getBoolean(TELEMETRY_ENABLED, false);
    }

    public static final String HIDE_SUBSCRIPTION_OFFERS = "hide_subscription_offers";

    public static boolean isHideSubscriptionOffersEnabled() {
        SharedPreferences preferences = getPreferences();
        // On by default, the offer screen cannot load its prices outside of Google Play anyway.
        return preferences == null || preferences.getBoolean(HIDE_SUBSCRIPTION_OFFERS, true);
    }

    public static void setHideSubscriptionOffersEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(HIDE_SUBSCRIPTION_OFFERS, enabled).apply();
    }

    public static void setTelemetryEnabled(boolean enabled) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) return;

        preferences.edit().putBoolean(TELEMETRY_ENABLED, enabled).apply();
    }
}
