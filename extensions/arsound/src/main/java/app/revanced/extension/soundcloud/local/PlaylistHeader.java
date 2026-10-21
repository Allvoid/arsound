package app.revanced.extension.soundcloud.local;

import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The header of the playlist and album screen.
 * <p>
 * SoundCloud writes "Playlist · 95 Tracks · 5:12:40", counting only the tracks on its server. The
 * number of tracks this phone can play without the network follows the number of tracks, after a
 * download arrow: tracks downloaded by Arsound and imported files of the playlist.
 * <p>
 * The header is drawn by Compose from the playlist's metadata. The list binds it again only when that
 * metadata changes, so a new cover or count binds the shown header again. The numbers come from
 * {@link PlaylistTracks}.
 */
public final class PlaylistHeader {
    /** The playlist whose header was drawn last. */
    private static volatile String shownPlaylist;
    /** The header bound last and its item, bound again to draw it anew. */
    private static WeakReference<Object> shownHolder = new WeakReference<>(null);
    private static WeakReference<Object> shownItem = new WeakReference<>(null);

    private PlaylistHeader() {
    }

    /**
     * Called with the track list of a playlist screen, off the main thread. The header, drawn before
     * the tracks arrived, is drawn again with the new count.
     *
     * @param urns The {@code Urn}s the screen shows.
     */
    static void onScreenTracks(String playlistUrn, List<?> urns) {
        if (PlaylistTracks.record(playlistUrn, urns)) Utils.runOnMainThread(() -> redraw(playlistUrn));
    }

    /**
     * Injection point: the header is bound to its item.
     *
     * @param holder The header's {@code ScViewHolder}.
     * @param item   The header item with the playlist's metadata.
     */
    public static void onBind(Object holder, Object item) {
        shownHolder = new WeakReference<>(holder);
        shownItem = new WeakReference<>(item);
    }

    /** Draws the shown header of the playlist again, with its current cover and count. Main thread. */
    public static void redraw(String playlistUrn) {
        try {
            Object holder = shownHolder.get();
            Object item = shownItem.get();
            if (holder == null || item == null || !playlistUrn.equals(shownPlaylist)) return;
            View view = (View) holder.getClass().getField("itemView").get(holder);
            if (view == null || !view.isAttachedToWindow()) return;
            holder.getClass().getMethod("bindItem", Object.class).invoke(holder, item);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not draw the playlist header again", ex);
        }
    }

    /**
     * Injection point: right before SoundCloud joins the parts of the header's meta line with " · ".
     *
     * @param parts    The parts: type, number of tracks, duration; each one only if known.
     * @param metadata {@code PlaylistDetailsMetadata}, which holds the playlist.
     */
    @SuppressWarnings("unchecked")
    public static void addDownloadedCount(List<Object> parts, Object metadata) {
        try {
            String playlistUrn = playlistUrnOf(metadata);
            if (playlistUrn == null) return;
            shownPlaylist = playlistUrn;
            Integer count = PlaylistTracks.downloadedCount(playlistUrn);
            if (count == null || count <= 0) return;
            // Right after the number of tracks: before the duration, which is last when it is there.
            int last = parts.size() - 1;
            boolean duration = last >= 0 && String.valueOf(parts.get(last)).matches("\\d+(:\\d{2}){1,2}");
            parts.add(duration ? last : parts.size(), "↓ " + count);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the downloaded count", ex);
        }
    }

    private static String playlistUrnOf(Object metadata) throws Exception {
        for (java.lang.reflect.Field field : metadata.getClass().getDeclaredFields()) {
            if (!field.getType().getName().endsWith(".PlaylistItem")) continue;
            field.setAccessible(true);
            Object item = field.get(metadata);
            return item == null ? null : String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
        }
        return null;
    }
}
