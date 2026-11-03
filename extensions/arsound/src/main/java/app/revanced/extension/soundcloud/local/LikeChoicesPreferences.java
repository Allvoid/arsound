package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Utils;

/** Keeps {@link LikeChoices} in the preferences of the local additions. */
final class LikeChoicesPreferences implements LikeChoices.Storage {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String KNOWN = "likes_known";
    private static final String LIKED = "likes_liked";
    private static final String UNLIKED = "likes_unliked";
    /** What the older version kept: the files the user took out of the likes. */
    private static final String LEGACY_HIDDEN = "likes_hidden";

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public LikeChoices.Snapshot load() {
        SharedPreferences preferences = preferences();
        // The sets returned by getStringSet must not be changed: copies are kept.
        if (preferences == null || !preferences.contains(KNOWN)) return null;
        return new LikeChoices.Snapshot(copy(preferences, KNOWN), copy(preferences, LIKED), copy(preferences, UNLIKED));
    }

    @Override
    public Set<String> legacyHidden() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : copy(preferences, LEGACY_HIDDEN);
    }

    @Override
    public void save(LikeChoices.Snapshot snapshot) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putStringSet(KNOWN, snapshot.known).putStringSet(LIKED, snapshot.liked)
                .putStringSet(UNLIKED, snapshot.unliked).apply();
    }

    private static Set<String> copy(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, new HashSet<>()));
    }
}
