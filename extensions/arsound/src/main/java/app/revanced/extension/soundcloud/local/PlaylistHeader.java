package app.revanced.extension.soundcloud.local;

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
 * The header is drawn by Compose from the playlist's metadata, so it changes when the playlist is
 * loaded again. The numbers come from {@link PlaylistTracks}.
 */
public final class PlaylistHeader {
    /** The playlist whose header was drawn last. */
    private static volatile String shownPlaylist;

    private PlaylistHeader() {
    }

    /**
     * Called with the track list of a playlist screen, off the main thread. The header, drawn before
     * the tracks arrived, is drawn again with the new count.
     *
     * @param urns The {@code Urn}s the screen shows.
     */
    static void onScreenTracks(String playlistUrn, List<?> urns) {
        if (!PlaylistTracks.record(playlistUrn, urns) || !playlistUrn.equals(shownPlaylist)) return;
        Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(playlistUrn));
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
