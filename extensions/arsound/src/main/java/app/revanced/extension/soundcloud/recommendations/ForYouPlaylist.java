package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.OwnPlaylists;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalPlaylists;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The playlist that holds "For you". It lives on this phone only ({@link LocalPlaylists}), and its tracks are added here.
 */
final class ForYouPlaylist {
    private static final Set<String> KNOWN_TITLES = new HashSet<>(Arrays.asList("Для вас", "For you"));

    private ForYouPlaylist() {
    }

    static String title() {
        return Lang.text("Для вас", "For you");
    }

    /** The playlist on this phone, made when it is missing; what an older version kept on the server moves to it. */
    static String ensure() throws Exception {
        String urn = LocalPlaylists.FOR_YOU;
        if (!LocalPlaylists.ensure(urn, title())) return null;
        String stored = ForYouPrefs.playlistUrn();
        if (!urn.equals(stored)) {
            if (stored != null) {
                OwnPlaylists.remember(stored);  // the app used it as its own: it may go
                LocalPlaylists.move(stored, urn);
            }
            ForYouPrefs.storePlaylistUrn(urn);
            ForYouPrefs.setServerCopiesLeft(true);
        }
        if (ForYouPrefs.serverCopiesLeft()) clearServerCopies();
        return urn;
    }

    /** Tried again on the next start when it fails: the playlist on the phone does not wait for it. */
    private static void clearServerCopies() {
        try {
            if (deleteServerCopies()) ForYouPrefs.setServerCopiesLeft(false);
        } catch (Exception ex) {
            Logger.printInfo(() -> "For you: the empty playlists on the account stay until the next start: " + ex);
        }
    }

    /** An older version kept the list as an empty playlist on SoundCloud: on the next start it leaves the account. */
    static void moveToPhone() throws Exception {
        if (ForYouPrefs.playlistUrn() != null) ensure();
    }

    /**
     * The empty playlists with the title that the app remembers making. One with the title alone may be the user's: it
     * stays. @return False when SoundCloud did not answer.
     */
    private static boolean deleteServerCopies() throws Exception {
        String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
        if (me[1] == null) return false;
        long userId = new JSONObject(me[1]).getLong("id");
        String[] response = DownloadTrackPatch.apiGet(
                "https://api-v2.soundcloud.com/users/" + userId + "/playlists_without_albums?limit=200");
        if (response[1] == null) return false;
        JSONArray playlists = new JSONObject(response[1]).optJSONArray("collection");
        for (int i = 0; playlists != null && i < playlists.length(); i++) {
            JSONObject playlist = playlists.getJSONObject(i);
            String copy = "soundcloud:playlists:" + playlist.getLong("id");
            boolean empty = KNOWN_TITLES.contains(playlist.optString("title")) && playlist.optInt("track_count", -1) == 0;
            if (!empty || !LocalAdditions.getEntries(copy).isEmpty() || !OwnPlaylists.isOwn(copy)) continue;
            int code = DownloadTrackPatch.apiDelete("https://api-v2.soundcloud.com/playlists/" + playlist.getLong("id"));
            Logger.printInfo(() -> "For you: deleted the empty playlist " + copy + " from the account, HTTP " + code);
        }
        return true;
    }
}
