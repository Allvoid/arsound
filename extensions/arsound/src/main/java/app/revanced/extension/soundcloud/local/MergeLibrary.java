package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.offline.PlaylistPreloader;

/** What the merge dialog needs: the playlist to merge into and the ones to take tracks from, in library order. */
final class MergeLibrary {
    MergeCandidate target;
    final List<MergeCandidate> sources = new ArrayList<>();

    /**
     * @param targetUrn    The playlist whose menu was opened.
     * @param menuEditable Whether the menu offered "Add music" for it, which says if SoundCloud accepts new tracks for it.
     * @return The playlists, or null if the library is not loaded. Blocks: call it off the main thread.
     */
    static MergeLibrary read(String targetUrn, boolean menuEditable) {
        // The database only: it works without a network and does not wait for a sync.
        List<Object> items = PlaylistPreloader.libraryItems("LOCAL_ONLY");
        if (items.isEmpty()) return null;
        MergeLibrary library = new MergeLibrary();
        for (Object item : items) {
            MergeCandidate candidate = candidate(item);
            if (candidate == null) continue;
            if (candidate.urn.equals(targetUrn)) {
                library.target = candidate;
            } else if (!candidate.generated && candidate.count > 0) {
                library.sources.add(candidate);
            }
        }
        if (library.target == null) {
            // A playlist that is not in the library, for example somebody else's: the menu says if it can be edited.
            String title = null;
            for (String[] recent : LocalAdditions.readRecentPlaylists()) {
                if (recent[0].equals(targetUrn)) title = recent[1];
            }
            if (title == null || title.isEmpty()) title = text("Этот плейлист", "This playlist");
            library.target = new MergeCandidate(targetUrn, title, -1, -1, null, menuEditable, false, false);
        }
        return library;
    }

    private static MergeCandidate candidate(Object item) {
        try {
            String urn = String.valueOf(call(item, "getUrn"));
            int soundCloudCount = (Integer) call(item, "getTracksCount");
            Object playlist = call(item, "getPlaylist");
            return new MergeCandidate(urn,
                    String.valueOf(call(item, "getTitle")),
                    PlaylistTracks.shownCount(urn, soundCloudCount),
                    PlaylistTracks.serverCount(urn, soundCloudCount),
                    (String) call(playlist, "getArtworkImageUrl"),
                    Boolean.TRUE.equals(call(call(item, "getPermissions"), "isEditable")),
                    Boolean.TRUE.equals(call(item, "isAlbum")),
                    Boolean.TRUE.equals(call(item, "isStation")) || Boolean.TRUE.equals(call(item, "isSystemPlaylist")));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read a library playlist", ex);
            return null;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
