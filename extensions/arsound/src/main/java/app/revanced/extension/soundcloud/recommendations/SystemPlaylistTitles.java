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

    /** @param urns The track urns of the playlist. */
    static void learn(List<Object> urns) {
        List<String> missing = KnownTracks.unknownIds(urns);
        for (int start = 0; start < missing.size(); start += TRACKS_PER_REQUEST) {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks?ids="
                        + String.join(",", missing.subList(start, Math.min(missing.size(), start + TRACKS_PER_REQUEST))));
                if (response[1] == null) return;
                JSONArray array = new JSONArray(response[1]);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.getJSONObject(i);
                    KnownTracks.remember(TrackUrn.TRACKS + track.getLong("id"),
                            new SongRef(track.optString("title"), track.optLong("full_duration", track.optLong("duration"))));
                }
            } catch (Exception ex) {
                // Offline: the playlist is shown as it is.
                Logger.printInfo(() -> "No titles for the system playlist check: " + ex);
                return;
            }
        }
    }
}
