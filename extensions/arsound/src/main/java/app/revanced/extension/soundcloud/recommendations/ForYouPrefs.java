package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Date;
import java.util.Set;

import app.revanced.extension.shared.Utils;

/** What "For you" keeps on the phone: its playlist, when it last tried, the songs found on SoundCloud. */
final class ForYouPrefs {
    private static final String PREFERENCES_NAME = "arsound_for_you";
    private static final String PLAYLIST_URN = "playlist_urn";
    /** The last refresh after which nothing is left for the day (filled, or nothing to add). */
    private static final String LAST_REFRESH = "last_refresh";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_STATUS = "last_status";
    private static final String MATCHES = "matches";
    private static final String PREVIOUS = "previous";

    private ForYouPrefs() {
    }

    static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The playlist urn, or null before the first refresh. */
    static String playlistUrn() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(PLAYLIST_URN, null);
    }

    static void storePlaylistUrn(String urn) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(PLAYLIST_URN, urn).apply();
    }

    /** When the playlist was last updated (or found nothing to add), 0 if never. */
    static long lastRefresh() {
        SharedPreferences preferences = preferences();
        return preferences == null ? 0 : preferences.getLong(LAST_REFRESH, 0);
    }

    /** What the last refresh did, with its date and time. */
    static String status() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return "";
        String status = preferences.getString(LAST_STATUS, "");
        long time = preferences.getLong(LAST_ATTEMPT, 0);
        if (time == 0) return status;
        String when = android.text.format.DateFormat.getDateFormat(Utils.getContext()).format(new Date(time))
                + " " + android.text.format.DateFormat.getTimeFormat(Utils.getContext()).format(new Date(time));
        return status.isEmpty() ? when : when + " · " + status;
    }

    static void recordAttempt(RefreshResult result) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(LAST_ATTEMPT, System.currentTimeMillis()).putString(LAST_STATUS, result.message);
        if (result.settlesTheDay()) editor.putLong(LAST_REFRESH, System.currentTimeMillis());
        editor.apply();
    }

    /** The matches kept from earlier days, as the JSON text they were stored in. */
    static String matches() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "{}" : preferences.getString(MATCHES, "{}");
    }

    /** The song keys of the last playlist. */
    static Set<String> previousKeys() {
        SharedPreferences preferences = preferences();
        return preferences == null ? Collections.emptySet() : preferences.getStringSet(PREVIOUS, Collections.emptySet());
    }

    static void storeMatches(String matches, Set<String> chosenKeys) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(MATCHES, matches).putStringSet(PREVIOUS, chosenKeys).apply();
    }
}
