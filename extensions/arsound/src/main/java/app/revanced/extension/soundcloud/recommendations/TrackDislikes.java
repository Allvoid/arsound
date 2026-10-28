package app.revanced.extension.soundcloud.recommendations;

import android.app.Dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * "Not for me" on a track: it is kept on this phone and the track no longer shows up in the
 * recommendations on the home screen and in autoplay. Likes, playlists and search are not touched.
 * <p>
 * The tracks live in {@link DislikeStore}, their titles are asked by {@link DislikeTitles} and the menu row is
 * {@link DislikeMenuRow}; this class is what the rest of the app calls.
 */
@SuppressWarnings("unused")
public final class TrackDislikes {
    public static final String ENABLED = "dislikes_enabled";

    private TrackDislikes() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    /** Whether a filter has anything to hide. */
    static boolean isActive() {
        return isEnabled() && !DislikeStore.isEmpty();
    }

    /** @param urn A track urn; sounds and tracks are the same. */
    public static boolean isDisliked(Object urn) {
        if (urn == null || !isEnabled()) return false;
        return DislikeStore.contains(TrackUrn.normalize(urn));
    }

    /** Disliked tracks, the newest last: urn to title. */
    public static synchronized Map<String, String> getAll() {
        return DislikeStore.copy();
    }

    public static synchronized void remove(String urn) {
        DislikeStore.remove(urn);
    }

    /** The songs of the disliked tracks, so a re-upload of the same song is not recommended either. */
    static synchronized List<SongRef> songs() {
        return isEnabled() ? DislikeStore.songs() : new ArrayList<>();
    }

    static synchronized void add(String urn) {
        DislikeStore.add(urn);
        DislikeTitles.fetch(urn);
    }

    /** Adds "Not for me" or "Undo not for me" to the track menu. */
    public static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        if (!isEnabled()) return;
        DislikeMenuRow.add(dialog, trackUrn);
    }
}
