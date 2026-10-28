package app.revanced.extension.soundcloud.local;

import java.io.File;

import app.revanced.extension.shared.Logger;

/** Telling the screens that a playlist, or a track in it, changed on this phone. */
final class PlaylistChanges {
    private PlaylistChanges() {
    }

    /**
     * Tells open playlist screens to reload their track list, and the library to show the new number
     * of tracks. SoundCloud's library list does not draw its cells again for a change that only the
     * local additions know about, so the numbers are read and drawn here.
     */
    static void playlistChanged(String playlistUrn) {
        PlaylistTracks.refreshLater(playlistUrn);
        LocalCovers.onPlaylistChanged(playlistUrn);
        PlaylistOps operations = PlaylistOps.current();
        if (operations == null) return;
        try {
            operations.notifyUpdated(operations.playlistUrn(playlistUrn));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not refresh playlist " + playlistUrn, ex);
        }
    }

    /** Tells the open playlists that show an imported file to load its track again, with a new cover. */
    static void trackChanged(File audio) {
        for (String playlist : PlaylistTracks.playlistsWithImportedTracks()) playlistChanged(playlist);
    }
}
