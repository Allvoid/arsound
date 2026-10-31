package app.revanced.extension.soundcloud.local;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

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
    private static final String ORDER_PREFIX = "track_order_";
    private static final String TRACK_ITEM_CLASS =
            "com.soundcloud.android.playlists.PlaylistDetailItem$PlaylistDetailTrackItem";
    /** Fields of the track item of the playlist screen, found in the decompiled app (the names are shrunk). */
    private static final String SUGGESTED_FIELD = "i";
    private static final String PLAYLIST_URN_FIELD = "c";

    private TrackOrder() {
    }

    /** The saved order of one playlist; tracks added later come after the arranged ones. */
    private static ManualOrder orderOf(String playlistUrn) {
        return new ManualOrder(ORDER_PREFIX + playlistUrn, ManualOrder.Unknown.LAST);
    }

    /** Forgets the manual order of a playlist, so it follows the order of its entries again. */
    static void clear(String playlistUrn) {
        orderOf(playlistUrn).clear();
    }

    /** Puts the track urns of a playlist in the saved order. */
    static List<Object> apply(String playlistUrn, List<Object> urns) {
        if (!Settings.isPlaylistOrderEnabled()) return urns;
        ManualOrder order = orderOf(playlistUrn);
        List<String> saved = order.read();
        if (saved.isEmpty()) return urns;
        return order.sort(urns, saved, String::valueOf);
    }

    public static void resetAll() {
        ManualOrder.forgetAll(ORDER_PREFIX);
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
        Object suggested = field(item, SUGGESTED_FIELD);
        return !Boolean.TRUE.equals(suggested);
    }

    private static void save(List<Object> rows) {
        String playlistUrn = null;
        List<Object> urns = new ArrayList<>();
        for (Object item : rows) {
            if (!isTrackRow(item)) continue;
            if (playlistUrn == null) playlistUrn = String.valueOf(field(item, PLAYLIST_URN_FIELD));
            try {
                urns.add(Urns.of(item));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read track urn", ex);
                return;
            }
        }
        if (playlistUrn != null) saveUrns(playlistUrn, urns);
    }

    /**
     * Saves the order of a playlist as the track urns, from first to last. The same as dragging a track does,
     * for code that changes the list itself.
     */
    static void saveUrns(String playlistUrn, List<?> urns) {
        orderOf(playlistUrn).save(urns);
    }

    /**
     * The playlist screen keeps the track list it loaded before the drag. The next update of the screen
     * (for example the playing track highlighted after a tap) is built from that list and puts the moved
     * track back, so the list is reloaded in the saved order once the track is dropped.
     */
    private static void reload(List<Object> rows) {
        for (Object item : rows) {
            if (!isTrackRow(item)) continue;
            String playlistUrn = String.valueOf(field(item, PLAYLIST_URN_FIELD));
            Utils.runOnBackgroundThread(() -> PlaylistChanges.playlistChanged(playlistUrn));
            return;
        }
    }

    /** A field of a row, looked up once per class: a drag reads every row of the playlist at each step. */
    private static Object field(Object item, String name) {
        try {
            return app.revanced.extension.soundcloud.shared.Getters.field(item, name);
        } catch (Exception ex) {
            return null;
        }
    }
}
