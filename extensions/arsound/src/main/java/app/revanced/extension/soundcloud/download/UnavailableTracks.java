package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /**
     * The verdicts that depend on where the user is and on the account (a region block, a subscription) are kept only
     * this long: another VPN or a subscription bought changes them, and a check right after must see it.
     */
    private static final long ACCOUNT_LIFETIME_MS = 30 * 60 * 1000L;
    private static final AtomicBoolean swept = new AtomicBoolean();

    private UnavailableTracks() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Drops the verdicts that ran out, once per process. {@link #get} removes only the one it is asked for, so
     * the verdict of a track that is never checked again would stay in the file for good.
     */
    private static void sweepOnce(SharedPreferences preferences) {
        if (!swept.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences.edit();
        boolean any = false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                editor.remove(entry.getKey());
                any = true;
            }
        }
        if (any) editor.apply();
    }

    /** A value that cannot be read counts as expired, like in {@link #get}. */
    private static boolean isExpired(Object value, long now) {
        try {
            String text = (String) value;
            long savedAt = Long.parseLong(text.substring(0, text.indexOf('|')));
            return now - savedAt > LIFETIME_MS;
        } catch (Exception ex) {
            return true;
        }
    }

    /** @return The remembered verdict, or null if there is none or it is too old. */
    static TrackSource get(String trackId) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        sweepOnce(preferences);
        String value = preferences.getString(trackId, null);
        if (value == null) return null;
        try {
            int separator = value.indexOf('|');
            long savedAt = Long.parseLong(value.substring(0, separator));
            TrackSource.Status status = TrackSource.Status.valueOf(value.substring(separator + 1));
            if (System.currentTimeMillis() - savedAt > lifetimeOf(status)) {
                preferences.edit().remove(trackId).apply();
                return null;
            }
            return TrackSource.unavailable(status);
        } catch (Exception ex) {
            preferences.edit().remove(trackId).apply();
            return null;
        }
    }

    /** How long a verdict holds: short for those that depend on the region and the account. */
    private static long lifetimeOf(TrackSource.Status status) {
        return status == TrackSource.Status.REGION_BLOCKED || status == TrackSource.Status.SUBSCRIPTION_REQUIRED
                ? ACCOUNT_LIFETIME_MS : LIFETIME_MS;
    }

    static void put(String trackId, TrackSource.Status status) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        sweepOnce(preferences);
        preferences.edit().putString(trackId, System.currentTimeMillis() + "|" + status.name()).apply();
    }
}
