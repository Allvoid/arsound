package app.revanced.extension.soundcloud.offline;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Plays tracks downloaded by Arsound from their file instead of streaming them.
 * <p>
 * The track keeps its SoundCloud identity (likes, comments, queue), only the audio source changes.
 * Playback works without a connection and does not use mobile data or radio power.
 */
@SuppressWarnings("unused")
public final class DownloadedPlaybackPatch {
    private DownloadedPlaybackPatch() {
    }

    /** Which way the file is opened, for the log: the log of a playback tells whether the media store was needed. */
    static String route(String source) {
        return source.startsWith("content:") ? "content uri" : "path";
    }

    /**
     * @param track A SoundCloud {@code Track}.
     * @return The path of the downloaded file to play, or the address of its row in the media store where the path
     * is closed to the app; null to stream as usual. Either is handed to the player as the file of the stream.
     */
    public static String playableFilePath(Object track) {
        try {
            Object urn = track.getClass().getMethod("getTrackUrn").invoke(track);
            String source = DownloadTrackPatch.playableSource(DownloadTrackPatch.parseTrackId(urn));
            if (source == null) return null;

            Logger.printInfo(() -> "Playing downloaded file for " + urn + " via " + route(source) + ": " + source);
            return source;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not look up downloaded file", ex);
            return null;
        }
    }
}
