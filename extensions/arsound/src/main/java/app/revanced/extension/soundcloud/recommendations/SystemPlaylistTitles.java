package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/** Asks SoundCloud for the titles and lengths of the tracks of a system playlist that are not known yet. */
final class SystemPlaylistTitles {
    private static final int TRACKS_PER_REQUEST = 50;

    private SystemPlaylistTitles() {
    }

    /**
     * The list of the playlist, and the start of its playback, wait for the titles at most this long: a playlist of a
     * thousand tracks took twenty requests one after another.
     */
    private static final long WAIT_MS = 1_500;

    /**
     * Learns the titles of the tracks not known yet: as many as fit in {@link #WAIT_MS} now, the rest in the
     * background, after which the playlist is read again and filtered with all of them.
     *
     * @param playlistUrn The playlist, read again when the rest of the titles is known.
     * @param urns        The track urns of the playlist.
     */
    static void learn(String playlistUrn, List<Object> urns) {
        List<String> missing = KnownTracks.unknownIds(urns);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        int start = 0;
        while (start < missing.size() && System.currentTimeMillis() < deadline) {
            if (!learnChunk(missing, start)) return;
            start += TRACKS_PER_REQUEST;
        }
        if (start >= missing.size()) return;
        List<String> rest = new java.util.ArrayList<>(missing.subList(start, missing.size()));
        app.revanced.extension.shared.Utils.runOnBackgroundThread(() -> {
            for (int from = 0; from < rest.size(); from += TRACKS_PER_REQUEST) {
                if (!learnChunk(rest, from)) return;
            }
            app.revanced.extension.shared.Utils.runOnMainThread(
                    () -> app.revanced.extension.soundcloud.local.LocalAdditions.notifyPlaylistChanged(playlistUrn));
        });
    }

    /** Asks for the titles of one request's worth of ids. @return False when it failed (offline): no more are asked. */
    private static boolean learnChunk(List<String> ids, int start) {
        try {
            String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks?ids="
                    + String.join(",", ids.subList(start, Math.min(ids.size(), start + TRACKS_PER_REQUEST))));
            if (response[1] == null) return false;
            JSONArray array = new JSONArray(response[1]);
            for (int i = 0; i < array.length(); i++) {
                JSONObject track = array.getJSONObject(i);
                KnownTracks.remember(TrackUrn.TRACKS + track.getLong("id"),
                        new SongRef(track.optString("title"), track.optLong("full_duration", track.optLong("duration"))));
            }
            return true;
        } catch (Exception ex) {
            // Offline: the playlist is shown as it is.
            Logger.printInfo(() -> "No titles for the system playlist check: " + ex);
            return false;
        }
    }
}
