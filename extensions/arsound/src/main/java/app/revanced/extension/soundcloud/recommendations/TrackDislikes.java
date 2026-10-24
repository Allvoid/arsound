package app.revanced.extension.soundcloud.recommendations;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * "Not for me" on a track: it is kept on this phone and the track no longer shows up in the
 * recommendations on the home screen and in autoplay. Likes, playlists and search are not touched.
 */
@SuppressWarnings("unused")
public final class TrackDislikes {
    public static final String ENABLED = "dislikes_enabled";
    private static final String PREFERENCES_NAME = "arsound_dislikes";
    /** Track urn to the title and length of the song, for its re-uploads. Kept apart: the list above holds titles. */
    private static final String SONGS_NAME = "arsound_dislike_songs";
    private static final String ROW_TAG = "arsound_dislike_row";

    /** Track urn to its title, read once. */
    private static volatile Map<String, String> dislikes;

    private TrackDislikes() {
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static Map<String, String> dislikes() {
        Map<String, String> result = dislikes;
        if (result != null) return result;
        result = new java.util.concurrent.ConcurrentHashMap<>();
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        dislikes = result;
        return result;
    }

    /** Whether a filter has anything to hide. */
    static boolean isActive() {
        return isEnabled() && !dislikes().isEmpty();
    }

    /** @param urn A track urn; sounds and tracks are the same. */
    public static boolean isDisliked(Object urn) {
        if (urn == null || !isEnabled()) return false;
        return dislikes().containsKey(String.valueOf(urn).replace("soundcloud:sounds:", "soundcloud:tracks:"));
    }

    /** Disliked tracks, the newest last: urn to title. */
    public static synchronized Map<String, String> getAll() {
        return new LinkedHashMap<>(dislikes());
    }

    public static synchronized void remove(String urn) {
        dislikes().remove(urn);
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(urn).apply();
        SharedPreferences songs = songPreferences();
        if (songs != null) songs.edit().remove(urn).apply();
    }

    private static SharedPreferences songPreferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(SONGS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The title and length in milliseconds of each disliked track, so a re-upload of the same song is not
     * recommended either. Tracks disliked before the length was kept have none.
     */
    static synchronized java.util.List<Object[]> songs() {
        java.util.List<Object[]> result = new java.util.ArrayList<>();
        SharedPreferences songs = songPreferences();
        if (songs == null || !isEnabled()) return result;
        for (Map.Entry<String, ?> entry : songs.getAll().entrySet()) {
            if (!dislikes().containsKey(entry.getKey())) continue;
            try {
                JSONObject song = new JSONObject(String.valueOf(entry.getValue()));
                long duration = song.optLong("dur");
                String title = song.optString("title");
                if (duration > 0 && !title.isEmpty()) result.add(new Object[]{title, duration});
            } catch (Exception ignored) {
                // A damaged entry: only its re-uploads are not caught.
            }
        }
        return result;
    }

    private static synchronized void add(String urn) {
        dislikes().put(urn, urn);
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(urn, urn).apply();
        // The title is for the list in the settings, the song for the "For you" playlist.
        String id = DownloadTrackPatch.parseTrackId(urn);
        Utils.runOnBackgroundThread(() -> {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id);
                if (response[1] == null) return;
                JSONObject track = new JSONObject(response[1]);
                JSONObject user = track.optJSONObject("user");
                String title = track.optString("title") + (user == null ? "" : " — " + user.optString("username"));
                synchronized (TrackDislikes.class) {
                    if (!dislikes().containsKey(urn)) return;
                    dislikes().put(urn, title);
                    SharedPreferences stored = preferences();
                    if (stored != null) stored.edit().putString(urn, title).apply();
                    long duration = track.optLong("full_duration", track.optLong("duration"));
                    SharedPreferences songs = songPreferences();
                    if (songs != null && duration > 0) songs.edit().putString(urn, new JSONObject()
                            .put("title", track.optString("title")).put("dur", duration).toString()).apply();
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "No title for the disliked track " + urn + ": " + ex);
            }
        });
    }

    /** Adds "Not for me" or "Undo not for me" to the track menu. */
    public static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        if (!isEnabled()) return;
        try {
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            String urn = String.valueOf(trackUrn).replace("soundcloud:sounds:", "soundcloud:tracks:");
            boolean disliked = isDisliked(urn);
            Context context = dialog.getContext();
            ViewGroup row = DownloadTrackPatch.createMenuRow(context,
                    disliked ? text("Вернуть в рекомендации", "Show in recommendations again")
                            : text("Не нравится — не рекомендовать", "Not for me: don't recommend"),
                    "ic_actions_close", v -> {
                        dialog.dismiss();
                        if (disliked) {
                            remove(urn);
                        } else {
                            add(urn);
                        }
                        Toast.makeText(context, disliked
                                ? text("Трек снова может попасть в рекомендации", "The track may be recommended again")
                                : text("Трек больше не появится в рекомендациях и автовоспроизведении",
                                "The track no longer shows up in recommendations and autoplay"), Toast.LENGTH_SHORT).show();
                    });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the dislike row", ex);
        }
    }
}
