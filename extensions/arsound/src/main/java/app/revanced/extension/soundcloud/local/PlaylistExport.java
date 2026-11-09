package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * "Export to M3U8" in a playlist's "⋮": the playlist as the screen shows it (local additions and the own order
 * included) written to a file the user picks ({@link M3uPlaylist} for the text). Titles of SoundCloud tracks not known
 * on the phone yet are loaded first, one after another; everything runs in the background.
 */
final class PlaylistExport {
    private static final long READ_TIMEOUT_SECONDS = 20;

    private PlaylistExport() {
    }

    /** The playlist's title as it was last opened, for the file name. */
    static String titleOf(String playlistUrn) {
        if (SavedPlaylist.isSavedPlaylist(playlistUrn)) return SavedPlaylist.title();
        for (String[] playlist : AdditionsStore.recentPlaylists()) {
            if (playlist[0].equals(playlistUrn) && !playlist[1].isEmpty()) return playlist[1];
        }
        return "playlist";
    }

    /** Asks where to save the file; the answer comes back to {@link #write}. */
    static void start(Context context, String playlistUrn) {
        ImportActivity.exportPlaylist(context, playlistUrn, M3uPlaylist.fileName(titleOf(playlistUrn)));
    }

    /** Writes the playlist to the document the user chose. */
    static void write(Context context, String playlistUrn, Uri target) {
        Utils.runOnBackgroundThread(() -> {
            List<Object> urns = screenList(playlistUrn);
            if (urns == null) {
                done(context, target, null);
                return;
            }
            List<String> soundCloud = new ArrayList<>();
            for (Object urn : urns) if (!Urns.isLocalFile(urn)) soundCloud.add(String.valueOf(urn));
            Runnable finish = () -> Utils.runOnBackgroundThread(() -> done(context, target, tracks(urns, playlistUrn)));
            if (!TrackTitles.rememberAll(soundCloud, finish)) finish.run();
        });
    }

    /** The list of the playlist screen, or null when SoundCloud does not give it. Blocks. */
    private static List<Object> screenList(String playlistUrn) {
        try {
            PlaylistOps operations = PlaylistOps.current();
            if (operations == null) return null;
            Object urn = operations.playlistUrn(playlistUrn);
            Object screen = LocalAdditions.appendToTrackUrns(operations.trackUrns(urn), urn);
            Object list = Rx.blockingFirst(screen, READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return list instanceof List ? new ArrayList<>((List<?>) list) : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist export: could not read " + playlistUrn, ex);
            return null;
        }
    }

    private static List<M3uPlaylist.Track> tracks(List<Object> urns, String playlistUrn) {
        List<M3uPlaylist.Track> tracks = new ArrayList<>();
        for (Object urn : urns) {
            if (Urns.isLocalFile(urn)) {
                try {
                    File file = Urns.fileOf(urn);
                    LocalMusic.Track track = LocalMusic.track(file);
                    long seconds = track.durationMs > 0 ? track.durationMs / 1000 : -1;
                    tracks.add(new M3uPlaylist.Track(seconds, M3uPlaylist.name(track.artist, track.title), file.getName()));
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Playlist export: a file is left out: " + ex);
                }
                continue;
            }
            String entry = String.valueOf(urn);
            if (!entry.startsWith(TrackEntry.PREFIX)) continue;
            String title = TrackTitles.get(entry);
            tracks.add(new M3uPlaylist.Track(-1, title == null ? "" : title,
                    M3uPlaylist.SOUNDCLOUD_TRACK_URL + entry.substring(TrackEntry.PREFIX.length())));
        }
        Logger.printInfo(() -> "Playlist export: " + tracks.size() + " tracks of " + playlistUrn);
        return tracks;
    }

    private static void done(Context context, Uri target, List<M3uPlaylist.Track> tracks) {
        boolean written = false;
        if (tracks != null) {
            try (OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
                if (out != null) {
                    out.write(M3uPlaylist.text(null, tracks).getBytes(StandardCharsets.UTF_8));
                    written = true;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist export: could not write " + target, ex);
            }
        }
        boolean ok = written;
        int count = tracks == null ? 0 : tracks.size();
        Utils.runOnMainThread(() -> Toast.makeText(context, ok
                ? text("Плейлист сохранён: треков — " + count, "Playlist saved: " + count + " tracks")
                : text("Не удалось сохранить плейлист", "Could not save the playlist"), Toast.LENGTH_SHORT).show());
    }
}
