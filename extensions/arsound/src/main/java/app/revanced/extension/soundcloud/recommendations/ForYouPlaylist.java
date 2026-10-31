package app.revanced.extension.soundcloud.recommendations;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.OwnPlaylists;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The private SoundCloud playlist that holds "For you": found again after a reinstall, created when there is none.
 * It stays empty on the server; its tracks are added on this phone.
 */
final class ForYouPlaylist {
    private static final Set<String> KNOWN_TITLES = new HashSet<>(Arrays.asList("Для вас", "For you"));

    private ForYouPlaylist() {
    }

    static String title() {
        return Lang.text("Для вас", "For you");
    }

    /** A playlist with the title, as the server lists it. */
    private static final class ServerCopy {
        final long id;
        /** -1 when the server does not say: then the playlist is not known to be empty. */
        final int trackCount;

        ServerCopy(long id, int trackCount) {
            this.id = id;
            this.trackCount = trackCount;
        }
    }

    /**
     * The "For you" playlist already on the server: the oldest empty one with a known title (the oldest one if none
     * is empty). Empty copies next to it (left by older versions or a failed check) are deleted, unless tracks were
     * added to them on this phone. Null if there is none or the server could not be asked; in the second case
     * nothing is created either.
     */
    private static String adoptFromServer() throws Exception {
        String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
        if (me[1] == null) throw new IOException("SoundCloud did not answer: HTTP " + me[0]);
        long userId = new JSONObject(me[1]).getLong("id");
        String[] response = DownloadTrackPatch.apiGet(
                "https://api-v2.soundcloud.com/users/" + userId + "/playlists_without_albums?limit=200");
        if (response[1] == null) throw new IOException("SoundCloud did not answer: HTTP " + response[0]);
        JSONArray playlists = new JSONObject(response[1]).optJSONArray("collection");
        List<ServerCopy> copies = new ArrayList<>();
        for (int i = 0; playlists != null && i < playlists.length(); i++) {
            JSONObject playlist = playlists.getJSONObject(i);
            if (KNOWN_TITLES.contains(playlist.optString("title"))) {
                copies.add(new ServerCopy(playlist.getLong("id"), playlist.optInt("track_count", -1)));
            }
        }
        if (copies.isEmpty()) return null;
        copies.sort((a, b) -> Long.compare(a.id, b.id));
        // Ours is empty on the server (its tracks live on the phone): a playlist with tracks there is someone's own,
        // and is never taken.
        ServerCopy adopted = null;
        for (ServerCopy copy : copies) {
            if (copy.trackCount == 0) {
                adopted = copy;
                break;
            }
        }
        if (adopted == null) return null;
        for (ServerCopy copy : copies) {
            if (copy == adopted) continue;
            if (copy.trackCount != 0) continue;
            String copyUrn = "soundcloud:playlists:" + copy.id;
            // Tracks added on this phone mean an older version used the copy.
            if (!LocalAdditions.getEntries(copyUrn).isEmpty()) continue;
            // Only a playlist the app remembers making is deleted; one with the title alone may be the user's.
            if (!OwnPlaylists.isOwn(copyUrn)) {
                Logger.printInfo(() -> "For you: left the empty playlist " + copy.id + ", not made by this app");
                continue;
            }
            int code = DownloadTrackPatch.apiDelete("https://api-v2.soundcloud.com/playlists/" + copy.id);
            Logger.printInfo(() -> "For you: deleted the extra copy " + copy.id + ", HTTP " + code);
        }
        String urn = "soundcloud:playlists:" + adopted.id;
        Logger.printInfo(() -> "For you: using the existing playlist " + urn);
        return urn;
    }

    /** The playlist on SoundCloud: the stored one, an existing empty one with the title, or a new one. */
    static String ensure() throws Exception {
        SharedPreferences preferences = ForYouPrefs.preferences();
        if (preferences == null) return null;
        String urn = ForYouPrefs.playlistUrn();
        if (urn != null) {
            String id = urn.substring(urn.lastIndexOf(':') + 1);
            String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/playlists/" + id);
            // Only a definite "not found" means it was deleted; network errors keep the playlist.
            if (!"404".equals(response[0])) return urn;
            Logger.printInfo(() -> "For you: the playlist was deleted, creating it again");
        }
        // After a reinstall or cleared data the stored urn is gone, but the playlist still exists on the server.
        // The server is asked first: right after a reset the library on the phone is still empty, and creating
        // a playlist then was how "Imported" got duplicated.
        String adopted = adoptFromServer();
        if (adopted != null) {
            ForYouPrefs.storePlaylistUrn(adopted);
            return adopted;
        }
        for (Object item : PlaylistPreloader.libraryItems("LOCAL_ONLY")) {
            try {
                String title = String.valueOf(item.getClass().getMethod("getTitle").invoke(item));
                int tracks = (Integer) item.getClass().getMethod("getTracksCount").invoke(item);
                if (tracks == 0 && KNOWN_TITLES.contains(title)) {
                    String found = String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
                    ForYouPrefs.storePlaylistUrn(found);
                    return found;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "For you: could not read a library playlist", ex);
            }
        }

        Object operations = SavedPlaylist.getPlaylistOperations();
        if (operations == null) return null;
        Object single = operations.getClass()
                .getMethod("createNewPlaylist", String.class, boolean.class, List.class)
                .invoke(operations, title(), false, Collections.emptyList());
        Object result = Rx.blockingFirst(single, 30, TimeUnit.SECONDS);
        if (result == null || !result.getClass().getName().endsWith("PlaylistCreationResult$Success")) {
            Logger.printInfo(() -> "For you: could not create the playlist: " + result);
            return null;
        }
        Object playlist = result.getClass().getMethod("getPlaylist").invoke(result);
        String created = String.valueOf(playlist.getClass().getMethod("getUrn").invoke(playlist));
        OwnPlaylists.remember(created);
        ForYouPrefs.storePlaylistUrn(created);
        Logger.printInfo(() -> "For you: created the playlist " + created);
        return created;
    }
}
