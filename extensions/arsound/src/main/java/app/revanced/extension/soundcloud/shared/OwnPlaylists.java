package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Utils;

/**
 * The playlists the app made on SoundCloud itself, remembered the moment they were made. Only these may ever be
 * deleted by the app: a playlist found by its title alone may be the user's own (an empty "Для вас" made by hand), and
 * a deletion on the server cannot be taken back. A copy that is not in this list is only hidden.
 */
public final class OwnPlaylists {
    private static final String PREFERENCES_NAME = "arsound_own_playlists";
    private static final String URNS = "urns";

    private OwnPlaylists() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Remembers a playlist the app has just made. */
    public static synchronized void remember(String urn) {
        SharedPreferences preferences = preferences();
        if (preferences == null || urn == null) return;
        Set<String> urns = new HashSet<>(preferences.getStringSet(URNS, new HashSet<>()));
        if (urns.add(urn)) preferences.edit().putStringSet(URNS, urns).apply();
    }

    /** Whether the app made this playlist itself. */
    public static synchronized boolean isOwn(String urn) {
        SharedPreferences preferences = preferences();
        return preferences != null && urn != null && preferences.getStringSet(URNS, new HashSet<>()).contains(urn);
    }
}
