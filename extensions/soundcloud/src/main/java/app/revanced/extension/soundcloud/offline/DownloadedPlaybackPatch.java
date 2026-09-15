package app.revanced.extension.soundcloud.offline;

import java.io.File;

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

    /**
     * @param track A SoundCloud {@code Track}.
     * @return The path of the downloaded file to play, or null to stream as usual.
     */
    public static String playableFilePath(Object track) {
        if (!Settings.isPlayDownloadedFilesEnabled()) return null;
        try {
            Object urn = track.getClass().getMethod("getTrackUrn").invoke(track);
            File file = DownloadTrackPatch.getDownloadedFile(DownloadTrackPatch.parseTrackId(urn));
            if (file == null) return null;

            Logger.printInfo(() -> "Playing downloaded file for " + urn + ": " + file);
            return file.getPath();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not look up downloaded file", ex);
            return null;
        }
    }
}
