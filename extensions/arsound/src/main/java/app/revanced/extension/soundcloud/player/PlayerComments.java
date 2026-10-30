package app.revanced.extension.soundcloud.player;

import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.BoolSettings;

/**
 * The comments that pop up over the full screen player while a track plays, and their marks on the waveform.
 * <p>
 * SoundCloud has a switch for them of its own ({@code PlayerSettings}, preference {@code player_popup_comments}),
 * deep in its settings. Arsound's switch answers for it ({@link #showPopups}) and also writes SoundCloud's preference
 * when the player's settings object is known, so an open player hides or shows them at once. Writing comments and
 * the comments screen are untouched.
 */
@SuppressWarnings("unused")
public final class PlayerComments {
    /** SoundCloud's own preference key. */
    private static final String POPUP_COMMENTS = "player_popup_comments";

    private static volatile WeakReference<Object> playerSettings = new WeakReference<>(null);

    private PlayerComments() {
    }

    public static boolean isHidden() {
        return BoolSettings.HIDE_PLAYER_COMMENTS.get();
    }

    /** Injection point: end of the {@code PlayerSettings} constructor. */
    public static void setPlayerSettings(Object settings) {
        playerSettings = new WeakReference<>(settings);
    }

    /** Injection point: the return of {@code PlayerSettings.a()}, whether comments pop up in the player. */
    public static boolean showPopups(boolean shown) {
        return shown && !isHidden();
    }

    /** Called by the settings switch after it stored the new value. */
    public static void onSettingChanged(boolean hidden) {
        Object settings = playerSettings.get();
        if (settings == null) return;
        try {
            for (Field field : settings.getClass().getDeclaredFields()) {
                if (!SharedPreferences.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                SharedPreferences preferences = (SharedPreferences) field.get(settings);
                // SoundCloud listens to its preference: the player that is open follows at once.
                if (preferences != null) preferences.edit().putBoolean(POPUP_COMMENTS, !hidden).apply();
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Player comments: could not tell SoundCloud's setting", ex);
        }
    }
}
