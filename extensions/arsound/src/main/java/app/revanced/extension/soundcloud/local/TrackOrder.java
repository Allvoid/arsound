package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Manual order of the tracks inside a playlist or album, on this device only.
 * <p>
 * Long press a track on the playlist screen and drag it, like playlists in the library. The order is
 * applied to the track list of the playlist, so the screen and playback follow it. Tracks that are not
 * in the saved order yet (added later) come after the arranged ones, in SoundCloud's order.
 */
@SuppressWarnings("unused")
public final class TrackOrder {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String ORDER_PREFIX = "track_order_";
    private static final String TRACK_ITEM_CLASS =
            "com.soundcloud.android.playlists.PlaylistDetailItem$PlaylistDetailTrackItem";

    private TrackOrder() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Forgets the manual order of a playlist, so it follows the order of its entries again. */
    static void clear(String playlistUrn) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(ORDER_PREFIX + playlistUrn).apply();
    }

    static boolean hasOrder(String playlistUrn) {
        SharedPreferences preferences = preferences();
        return Settings.isPlaylistOrderEnabled() && preferences != null && preferences.contains(ORDER_PREFIX + playlistUrn);
    }

    /** Puts the track urns of a playlist in the saved order. */
    static List<Object> apply(String playlistUrn, List<Object> urns) {
        SharedPreferences preferences = preferences();
        if (preferences == null || !Settings.isPlaylistOrderEnabled()) return urns;
        String value = preferences.getString(ORDER_PREFIX + playlistUrn, null);
        if (value == null || value.isEmpty()) return urns;

        Map<String, Integer> positions = new HashMap<>();
        String[] saved = value.split("\n");
        for (int i = 0; i < saved.length; i++) positions.put(saved[i], i);
        List<Object> result = new ArrayList<>(urns);
        // Stable sort: new tracks keep SoundCloud's order among themselves, after the arranged ones.
        Collections.sort(result, (first, second) -> Integer.compare(
                positions.getOrDefault(String.valueOf(first), Integer.MAX_VALUE),
                positions.getOrDefault(String.valueOf(second), Integer.MAX_VALUE)));
        return result;
    }

    public static void resetAll() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(ORDER_PREFIX)) editor.remove(key);
        }
        editor.apply();
    }

    /** Injection point. Called when the playlist screen has created its views. */
    public static void attach(View root) {
        if (!Settings.isPlaylistOrderEnabled() || root == null) return;
        root.post(() -> {
            try {
                View list = root.findViewById(Utils.getResourceIdentifier(
                        app.revanced.extension.shared.ResourceType.ID, "recycler_view"));
                if (!(list instanceof ViewGroup)) {
                    Logger.printInfo(() -> "Track order: no list found");
                    return;
                }
                new DragReorder((ViewGroup) list, TrackOrder::isTrackRow, TrackOrder::save)
                        .onDropped(TrackOrder::reload)
                        .install();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not set up track rearranging", ex);
            }
        });
    }

    private static boolean isTrackRow(Object item) {
        if (!item.getClass().getName().equals(TRACK_ITEM_CLASS)) return false;
        // Suggested tracks under the playlist are not part of it.
        Object suggested = field(item, "i");
        return !Boolean.TRUE.equals(suggested);
    }

    private static void save(List<Object> rows) {
        String playlistUrn = null;
        StringBuilder order = new StringBuilder();
        for (Object item : rows) {
            if (!isTrackRow(item)) continue;
            if (playlistUrn == null) playlistUrn = String.valueOf(field(item, "c"));
            try {
                order.append(item.getClass().getMethod("getUrn").invoke(item)).append('\n');
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read track urn", ex);
                return;
            }
        }
        SharedPreferences preferences = preferences();
        if (playlistUrn == null || preferences == null) return;
        preferences.edit().putString(ORDER_PREFIX + playlistUrn, order.toString()).apply();
    }

    /**
     * The playlist screen keeps the track list it loaded before the drag. The next update of the screen
     * (for example the playing track highlighted after a tap) is built from that list and puts the moved
     * track back, so the list is reloaded in the saved order once the track is dropped.
     */
    private static void reload(List<Object> rows) {
        for (Object item : rows) {
            if (!isTrackRow(item)) continue;
            String playlistUrn = String.valueOf(field(item, "c"));
            Utils.runOnBackgroundThread(() -> LocalAdditions.notifyPlaylistChanged(playlistUrn));
            return;
        }
    }

    private static Object field(Object item, String name) {
        try {
            Field field = item.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(item);
        } catch (Exception ex) {
            return null;
        }
    }
}
