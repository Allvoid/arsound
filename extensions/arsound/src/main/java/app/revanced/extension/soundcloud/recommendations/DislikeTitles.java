package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONObject;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/** Asks SoundCloud for the title and length of a track that was just disliked, off the main thread. */
final class DislikeTitles {
    private DislikeTitles() {
    }

    /**
     * The title is for the list in the settings, the song for My Wave.
     *
     * @param urn A track urn of the "tracks" kind.
     */
    static void fetch(String urn) {
        String id = DownloadTrackPatch.parseTrackId(urn);
        Utils.runOnBackgroundThread(() -> {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id);
                if (response[1] == null) return;
                JSONObject track = new JSONObject(response[1]);
                JSONObject user = track.optJSONObject("user");
                String title = track.optString("title") + (user == null ? "" : " — " + user.optString("username"));
                synchronized (TrackDislikes.class) {
                    if (!DislikeStore.contains(urn)) return;
                    DislikeStore.setTitle(urn, title);
                    long duration = track.optLong("full_duration", track.optLong("duration"));
                    if (duration > 0) DislikeStore.saveSong(urn, track.optString("title"), duration);
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "No title for the disliked track " + urn + ": " + ex);
            }
        });
    }
}
