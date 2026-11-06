package app.revanced.extension.soundcloud.local;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * What an earlier version left on the phone of the "For you" playlist, which is gone: its nightly job, its playlist
 * (it lived on this phone only) and its memory. The playlist is taken out of the library once; nothing makes it again.
 * A phone that never had it finds nothing to do.
 */
public final class LegacyForYou {
    /** The id the nightly job was planned with. */
    private static final int JOB_ID = 0x41F0;
    private static final String PREFERENCES_FOR_YOU = "arsound_for_you";
    private static final String DONE = "for_you_removed";
    private static final String STORED_TITLE = "local_playlist_title:";

    private LegacyForYou() {
    }

    /** Stops the nightly job planned by an earlier version. */
    public static void cancelJob() {
        try {
            Context context = Utils.getContext();
            if (context == null) return;
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not cancel the old For you job", ex);
        }
    }

    /**
     * Takes the playlist out of the library and clears what it kept, once. Blocks; runs on a background thread.
     * Tried again on the next start when the library is not ready yet.
     */
    public static void removePlaylist() {
        try {
            Context context = Utils.getContext();
            SharedPreferences preferences = SavedPlaylist.preferences();
            if (context == null || preferences == null || preferences.getBoolean(DONE, false)) return;
            Object posts = SavedPlaylist.postsStorage();
            Object operations = SavedPlaylist.playlistOperations();
            if (posts == null || operations == null) return;
            String urn = LocalPlaylists.FOR_YOU;
            ClassLoader loader = operations.getClass().getClassLoader();
            Object playlist = Urns.forPlaylist(loader, Urns.idOf(urn));
            posts.getClass().getMethod("removePlaylistPost", Urns.urnClass(loader)).invoke(posts, playlist);
            AdditionsStore.setEntries(urn, Collections.emptyList());
            preferences.edit().remove(STORED_TITLE + urn).putBoolean(DONE, true).apply();
            context.deleteSharedPreferences(PREFERENCES_FOR_YOU);
            PlaylistOps playlistOps = PlaylistOps.current();
            if (playlistOps != null) playlistOps.notifyUpdated(playlistOps.playlistUrn(urn));
            Logger.printInfo(() -> "The old For you playlist is out of the library");
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove the old For you playlist", ex);
        }
    }
}
