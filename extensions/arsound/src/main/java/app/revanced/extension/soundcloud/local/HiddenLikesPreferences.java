package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Utils;

/** Keeps {@link HiddenLikes} in the preferences of the local additions. */
final class HiddenLikesPreferences implements HiddenLikes.Storage {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String HIDDEN = "likes_hidden";

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public Set<String> load() {
        SharedPreferences preferences = preferences();
        // The set returned by getStringSet must not be changed.
        return preferences == null ? null : new HashSet<>(preferences.getStringSet(HIDDEN, new HashSet<>()));
    }

    @Override
    public void save(Set<String> paths) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putStringSet(HIDDEN, paths).apply();
    }
}
