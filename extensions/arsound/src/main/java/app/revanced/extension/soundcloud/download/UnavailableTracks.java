package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/**
 * Tracks a playlist check found impossible to download, kept for a few hours.
 * <p>
 * Finding out that a track is locked takes many requests to several clients, up to half a minute
 * per track. A second check of the same playlist reuses the answer instead of asking again.
 */
final class UnavailableTracks {
    private static final String PREFERENCES_NAME = "arsound_unavailable_tracks";
    private static final long LIFETIME_MS = 12 * 60 * 60 * 1000L;

    private UnavailableTracks() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** @return The remembered verdict, or null if there is none or it is too old. */
    static TrackSource get(String trackId) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        String value = preferences.getString(trackId, null);
        if (value == null) return null;
        try {
            int separator = value.indexOf('|');
            long savedAt = Long.parseLong(value.substring(0, separator));
            if (System.currentTimeMillis() - savedAt > LIFETIME_MS) {
                preferences.edit().remove(trackId).apply();
                return null;
            }
            return TrackSource.unavailable(TrackSource.Status.valueOf(value.substring(separator + 1)));
        } catch (Exception ex) {
            preferences.edit().remove(trackId).apply();
            return null;
        }
    }

    static void put(String trackId, TrackSource.Status status) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putString(trackId, System.currentTimeMillis() + "|" + status.name()).apply();
    }
}
