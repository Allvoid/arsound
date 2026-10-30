package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

import app.revanced.extension.soundcloud.shared.AppResources;

/**
 * Finds the icon of a settings row by a short key such as "wallpaper" or "storage". The row's own picture
 * {@code arsound_set_<key>} (copied into the app by the settings resource patch) comes first, then SoundCloud's own
 * drawables in the order of the table below; a key without any picture gives null and the row simply has no icon. So a
 * missing resource can never break a screen. Used by {@link SettingRow}.
 */
final class SettingsIcons {
    private static final String OWN_PREFIX = "arsound_set_";
    /** Key to SoundCloud drawables to try, best first. Keys with only an own picture are not listed. */
    private static final Map<String, String[]> SOUNDCLOUD = new HashMap<>();

    static {
        put("appearance", "ic_actions_sliders_three");
        put("theme", "arsound_set_appearance", "ic_actions_sliders_three");
        put("wallpaper", "ic_actions_image");
        put("home", "ic_actions_navigation_home");
        put("animation", "ic_actions_star_sparkle");
        put("player", "ic_actions_play_outlined", "ic_actions_playback_play_circle");
        put("widget", "ic_actions_heart");
        put("language", "ic_labels_distribution_globe");
        put("appicon", "ic_actions_circle");
        put("network", "ic_labels_distribution_globe");
        put("dns", "ic_actions_link");
        put("region", "ic_labels_location", "ic_actions_block");
        put("playback", "ic_actions_play_outlined");
        put("offline", "ic_actions_cloud_offline");
        put("retry", "ic_actions_repeat");
        put("mix", "ic_actions_playlist_queue");
        put("lyrics", "arsound_lyrics", "ic_actions_message_bubble");
        put("ai", "ic_actions_star_sparkle");
        put("ads", "ic_actions_block");
        put("block", "ic_actions_block");
        put("subscription", "ic_actions_premium_diamond");
        put("screenshot", "ic_actions_camera");
        put("recs", "ic_actions_star_sparkle");
        put("duplicates", "ic_actions_copy");
        put("dislike", "ic_actions_thumbs_down");
        put("music", "ic_actions_musical_note");
        put("import", "ic_actions_import");
        put("order", "ic_actions_move_handle");
        put("folder", "ic_actions_playlist");
        put("storage", "ic_actions_downloaded");
        put("cache", "ic_actions_cloud_offline");
        put("trash", "ic_actions_delete_bin");
        // The timer icon is a hairline stroke that does not show in a tile; the history clock is filled.
        put("clock", "ic_actions_listening_history_dark", "ic_actions_timer");
        put("eq", "ic_actions_sliders_vertical");
        put("volume", "ic_actions_volume_high_light");
        put("stats", "ic_actions_insights");
        put("battery", "ic_actions_lightning");
        put("privacy", "ic_actions_lock_closed");
        put("update", "ic_actions_replace_file");
        put("link", "ic_actions_link");
        put("reset", "ic_actions_warning");
        put("developer", "ic_actions_cog");
        put("account", "ic_actions_user");
        put("key", "ic_actions_lock_open");
        put("adult", "ic_actions_warning");
        put("log", "ic_actions_message_bubble");
        put("check", "ic_actions_checkmark");
        put("info", "ic_actions_info");
        put("cog", "ic_actions_cog");
        put("chevron", "ic_actions_chevron_right");
    }

    private SettingsIcons() {
    }

    private static void put(String key, String... names) {
        SOUNDCLOUD.put(key, names);
    }

    /** The resource names tried for a key, best first. */
    static String[] candidates(String key) {
        String[] fallback = SOUNDCLOUD.get(key);
        String[] names = new String[1 + (fallback == null ? 0 : fallback.length)];
        names[0] = OWN_PREFIX + key;
        if (fallback != null) System.arraycopy(fallback, 0, names, 1, fallback.length);
        return names;
    }

    /** The picture for the key, or null when neither the own picture nor a SoundCloud one exists. */
    static Drawable icon(Context context, String key) {
        for (String name : candidates(key)) {
            int id = AppResources.identifier(context, name, "drawable");
            if (id == 0) continue;
            try {
                Drawable drawable = context.getDrawable(id);
                if (drawable != null) return drawable;
            } catch (RuntimeException ignored) {
                // A drawable that cannot be made is as good as a missing one: try the next.
            }
        }
        return null;
    }
}
