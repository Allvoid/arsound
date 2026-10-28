package app.revanced.extension.soundcloud.settings;

import android.content.SharedPreferences;

/**
 * One on/off setting: its preference key, its default and how it is written. The settings screens bind a toggle to
 * one of these, so a row reads and writes its value itself; {@link Settings} keeps the {@code isX}/{@code setX}
 * methods the rest of the mod calls, as thin delegates.
 */
public final class BoolSetting {
    private final String key;
    private final boolean defaultValue;
    private final boolean writeNow;

    /** Written in the background ({@code apply}). */
    BoolSetting(String key, boolean defaultValue) {
        this(key, defaultValue, false);
    }

    /**
     * @param writeNow Written to the disk before {@link #set} returns, for a value the app restarts right after.
     */
    BoolSetting(String key, boolean defaultValue, boolean writeNow) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.writeNow = writeNow;
    }

    public String key() {
        return key;
    }

    /** The stored value; the default while the app context is not available yet. */
    public boolean get() {
        SharedPreferences preferences = Settings.preferences();
        return preferences == null ? defaultValue : preferences.getBoolean(key, defaultValue);
    }

    public void set(boolean value) {
        SharedPreferences preferences = Settings.preferences();
        if (preferences == null) return;

        SharedPreferences.Editor editor = preferences.edit().putBoolean(key, value);
        if (writeNow) editor.commit();
        else editor.apply();
    }
}
