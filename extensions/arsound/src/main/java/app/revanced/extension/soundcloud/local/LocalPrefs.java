package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/** The preferences of what is kept only on this phone. The default file is shared by local additions, titles, manual orders and the saved playlist. */
final class LocalPrefs {
    /** Users' saved data lives in this file: the name must not change. */
    private static final String NAME = "arsound_local_additions";

    private LocalPrefs() {
    }

    /** @return The shared preferences, or null while the app has no context yet. */
    static SharedPreferences open() {
        return open(NAME);
    }

    /** @return The preferences file with this name, or null while the app has no context yet. */
    static SharedPreferences open(String fileName) {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
    }
}
