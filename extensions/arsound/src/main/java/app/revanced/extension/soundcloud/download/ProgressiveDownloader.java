package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.function.BooleanSupplier;

import app.revanced.extension.shared.Logger;

/**
 * Saves a track that SoundCloud offers as one file, into a location the download manager cannot write to (a folder the
 * user picked): the file is fetched into the cache of the app, then copied into the location, like an HLS track.
 * <p>
 * Where the download manager can write, it fetches the file itself and does so in the background even when the app
 * is closed ({@link DownloadTrackPatch#enqueue}); this class is the way into the other locations. It runs on the
 * same pool as the HLS assembly and is counted the same way, so the progress counter, the spinner of the track and
 * the check of a playlist see no difference.
 */
final class ProgressiveDownloader {
    private static final String USER_AGENT = "Arsound";

    private ProgressiveDownloader() {
    }

    /**
     * Downloads the file of a track and saves it into a location. Runs on the calling background thread.
     *
     * @param location  Where the file is saved, read once when the download started.
     * @param cancelled Asked while the file arrives: once true, the rest is not downloaded and the result is null.
     * @return The saved file with its location and row, or null if the track could not be saved.
     * @throws HlsDownloader.NoSpaceException If the phone has no room for the track.
     */
    static DownloadIndex.Ref download(Context context, String trackId, TrackSource track, DownloadLocation location,
                                      BooleanSupplier cancelled) throws HlsDownloader.NoSpaceException {
        HlsDownloader.sweepStaleFiles(context);
        File cache = context.getCacheDir();
        File part = new File(cache, HlsDownloader.FILE_PREFIX + trackId + ".file");
        try {
            HlsDownloader.requireFreeSpace(cache, HlsDownloader.MIN_FREE_BYTES);
            if (!fetch(track.url, part, cancelled, cache) || cancelled.getAsBoolean()) {
                Logger.printInfo(() -> "The download of " + trackId + " was cancelled");
                return null;
            }
            // Another track may already have this name (two uploads of one song): the id tells them apart.
            String fileName = DownloadTrackPatch.freeFileName(location,
                    DownloadTrackPatch.progressiveFileName(trackId, track), trackId);
            return HlsDownloader.publish(context, location, part, fileName, track.title, track.artist);
        } catch (HlsDownloader.NoSpaceException ex) {
            Logger.printInfo(() -> "Download of " + trackId + " has no room: " + ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            // A write that failed on a full storage says only "ENOSPC" in a message that differs between systems.
            if (cache.getUsableSpace() < HlsDownloader.MIN_FREE_BYTES / 2) {
                HlsDownloader.NoSpaceException noSpace = new HlsDownloader.NoSpaceException("The storage ran full: " + ex);
                Logger.printInfo(() -> "Download of " + trackId + " has no room: " + noSpace.getMessage());
                throw noSpace;
            }
            Logger.printException(() -> "Download failure for " + trackId, ex);
            return null;
        } finally {
            HlsDownloader.delete(part);
        }
    }

    /**
     * Fetches the file into {@code target}. A connection that breaks half way is picked up where it stopped, when the
     * server allows it, and starts over when it does not.
     *
     * @return False if {@code cancelled} turned true before the file was complete.
     */
    private static boolean fetch(String url, File target, BooleanSupplier cancelled, File cache) throws Exception {
        long have = 0;
        long total = -1;
        int failures = 0;
        while (true) {
            HttpURLConnection connection = null;
            try {
                connection = DownloadHttp.open(url, have, USER_AGENT);
                // 206 is the rest of the file; 200 is all of it again, from the start.
                boolean resumed = have > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL;
                if (!resumed) have = 0;
                long length = connection.getContentLengthLong();
                if (total < 0 && length > 0) {
                    total = have + length;
                    HlsDownloader.requireFreeSpace(cache, length + HlsDownloader.MIN_FREE_BYTES);
                }
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target, resumed)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (cancelled.getAsBoolean()) return false;
                        output.write(buffer, 0, read);
                        have += read;
                    }
                }
                if (total >= 0 && have < total) throw new IOException("The file ended at " + have + " of " + total + " bytes");
                return true;
            } catch (IOException ex) {
                if (ex instanceof HlsDownloader.NoSpaceException || failures >= DownloadHttp.MAX_RETRIES
                        || !DownloadHttp.isTransient(ex) || Thread.currentThread().isInterrupted()) {
                    throw ex;
                }
                long pause = DownloadHttp.pauseBeforeRetry(ex, failures++);
                long kept = have;
                Logger.printInfo(() -> "Download of " + url + " failed after " + kept + " bytes (" + ex + "), trying again in "
                        + pause + " ms");
                Thread.sleep(pause);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }
}
