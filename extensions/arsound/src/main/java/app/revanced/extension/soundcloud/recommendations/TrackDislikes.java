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
    }

    private static synchronized void add(String urn) {
        dislikes().put(urn, urn);
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(urn, urn).apply();
        // The title is only for the list in the settings.
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
