package app.revanced.extension.soundcloud.local;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Manual order of the playlists in the library.
 * <p>
 * A long press on a playlist starts the rearrange mode: the rows wiggle and the pressed one follows the
 * finger. Another long press drags another playlist, a tap ends the mode. The order is saved on this
 * device and applied on top of SoundCloud's sorting; playlists that are not in the saved order yet
 * (new ones) come first.
 * <p>
 * The drag is {@link DragReorder}.
 */
@SuppressWarnings("unused")
public final class PlaylistOrder {
    private static final String ORDER_KEY = "playlist_order";
    private static final String PLAYLIST_ITEM_CLASS =
            "com.soundcloud.android.features.library.playlists.PlaylistCollectionItem$Playlist";
    /** New playlists come first, so the user sees them. */
    private static final ManualOrder ORDER = new ManualOrder(ORDER_KEY, ManualOrder.Unknown.FIRST);

    private PlaylistOrder() {
    }

    public static void reset() {
        ORDER.clear();
    }

    /**
     * Injection point. Called with the sorted library playlists ({@code List<PlaylistItem>}).
     *
     * @return The playlists in the saved order, new playlists first.
     */
    public static List<?> applyOrder(List<?> playlists) {
        if (playlists == null || !Settings.isPlaylistOrderEnabled()) return playlists;
        List<String> order = ORDER.read();
        if (order.isEmpty()) return playlists;
        try {
            // Stable sort keeps SoundCloud's order among new playlists.
            return ORDER.sort(playlists, order, Urns::textOrNull);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not apply the playlist order", ex);
            return playlists;
        }
    }

    /**
     * Injection point. Called with the rows the library playlists screen is about to show
     * ({@code List<PlaylistCollectionItem>}), also when it shows a list it kept in memory from before a
     * rearrange. Playlist rows are put in the saved order; headers stay where they are.
     */
    public static List<?> orderScreenItems(List<?> rows) {
        if (rows == null || !Settings.isPlaylistOrderEnabled()) return rows;
        List<String> order = ORDER.read();
        if (order.isEmpty()) return rows;
        try {
            List<Integer> slots = new ArrayList<>();
            List<Object> playlists = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                if (row != null && row.getClass().getName().equals(PLAYLIST_ITEM_CLASS)) {
                    slots.add(i);
                    playlists.add(row);
                }
            }
            if (playlists.size() < 2) return rows;
            List<?> ordered = applyOrder(playlists);
            List<Object> result = new ArrayList<>(rows);
            for (int i = 0; i < slots.size(); i++) result.set(slots.get(i), ordered.get(i));
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not order the playlists screen", ex);
            return rows;
        }
    }

    /** Injection point. Called when the playlist collection screen has created its views. */
    public static void attach(Object fragment, View root) {
        if (!Settings.isPlaylistOrderEnabled() || root == null
                || !fragment.getClass().getSimpleName().equals("MyPlaylistCollectionFragment")) return;
        root.post(() -> {
            try {
                ViewGroup recycler = findRecyclerView(root);
                if (recycler == null) {
                    Logger.printInfo(() -> "Playlist order: no list found");
                    return;
                }
                new DragReorder(recycler, PlaylistOrder::isPlaylistRow, PlaylistOrder::save).install();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not set up playlist rearranging", ex);
            }
        });
    }

    private static ViewGroup findRecyclerView(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("androidx.recyclerview.widget.RecyclerView")) return (ViewGroup) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findRecyclerView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isPlaylistRow(Object item) {
        return item.getClass().getName().equals(PLAYLIST_ITEM_CLASS);
    }

    private static void save(List<Object> rows) {
        List<String> urns = new ArrayList<>();
        for (Object item : rows) {
            if (!isPlaylistRow(item)) continue;
            String urn = Urns.textOrNull(item);
            if (urn != null) urns.add(urn);
        }
        ORDER.save(urns);
    }
}
