package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import app.revanced.extension.shared.Logger;

/**
 * Saves a track that SoundCloud offers as one file, into a location the download manager cannot write to (a folder the
 * user picked): the app fetches the file itself and writes it into the folder as it arrives.
 * <p>
 * Nothing is staged in the cache of the phone first, unlike an HLS track, which has to be a file on the phone to be
 * packed. The room of the phone is often the very reason a folder on a card is picked, and a download of an author
 * can be hundreds of megabytes.
 * <p>
 * Where the download manager can write, it fetches the file itself and does so in the background even when the app
 * is closed ({@link DownloadTrackPatch#enqueue}); this class is the way into the other locations. It runs on the
 * same pool as the HLS assembly and is counted the same way, so the progress counter, the spinner of the track and
 * the check of a playlist see no difference.
 */
final class ProgressiveDownloader {
    private static final String USER_AGENT = "Arsound";
    /** Room that is left on a card when a file fits, so the card is not filled to the last byte. */
    private static final long CARD_RESERVE_BYTES = 8L * 1024 * 1024;

    private ProgressiveDownloader() {
    }

    /**
     * Downloads the file of a track into a location. Runs on the calling background thread.
     *
     * @param location  Where the file is saved, read once when the download started.
     * @param cancelled Asked while the file arrives: once true, the rest is not downloaded and the result is null.
     * @return The saved file with its location and row, or null if the track could not be saved.
     * @throws HlsDownloader.NoSpaceException If the location has no room for the track.
     */
    static DownloadIndex.Ref download(Context context, String trackId, TrackSource track, DownloadLocation location,
                                      BooleanSupplier cancelled) throws HlsDownloader.NoSpaceException {
        // Another track may already have this name (two uploads of one song): the id tells them apart.
        String fileName = DownloadTrackPatch.freeFileName(location,
                DownloadTrackPatch.progressiveFileName(trackId, track), trackId);
        DownloadLocation.Writing writing = null;
        boolean finished = false;
        try {
            writing = location.startFile(context, fileName, FileNames.mimeTypeOf(fileName));
            long written = fetch(track, fileName, writing, cancelled);
            if (written < 0 || cancelled.getAsBoolean()) {
                Logger.printInfo(() -> "The download of " + trackId + " was cancelled");
                return null;
            }
            DownloadLocation.Saved saved = writing.finish();
            finished = true;
            DownloadIndex.Ref file = new DownloadIndex.Ref(location.id(), saved.name,
                    saved.uri == null ? null : saved.uri.toString());
            DownloadIndex.noteSaved(context, file, written);
            return file;
        } catch (HlsDownloader.NoSpaceException ex) {
            Logger.printInfo(() -> "Download of " + trackId + " has no room: " + ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            // A write that failed on a full storage says only "ENOSPC" in a message that differs between systems.
            if (DownloadLocation.isFull(ex)) {
                HlsDownloader.NoSpaceException noSpace = new HlsDownloader.NoSpaceException("No room left in " + location.label());
                Logger.printInfo(() -> "Download of " + trackId + " has no room: " + ex);
                throw noSpace;
            }
            Logger.printException(() -> "Download failure for " + trackId, ex);
            return null;
        } finally {
            // A half written file is not left in the folder, whether the download was cancelled or failed.
            if (writing != null && !finished) writing.abort();
        }
    }

    /**
     * Fetches the file into {@code writing}. A connection that breaks half way is picked up where it stopped, when the
     * server allows it, and starts over when it does not.
     *
     * @return How many bytes were written, or -1 if {@code cancelled} turned true before the file was complete.
     */
    private static long fetch(TrackSource track, String fileName, DownloadLocation.Writing writing, BooleanSupplier cancelled)
            throws Exception {
        String url = track.url;
        // The bytes of the server's file that are written, and the tag put in front of them.
        long have = 0;
        long tagBytes = 0;
        long total = -1;
        int failures = 0;
        while (true) {
            HttpURLConnection connection = null;
            try {
                connection = DownloadHttp.open(url, have, USER_AGENT);
                // 206 is the rest of the file; 200 is all of it again, from the start.
                boolean resumed = have > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL;
                // Anything already written (also only the tag) is thrown away: the file starts over.
                if (!resumed && (have > 0 || tagBytes > 0)) {
                    writing.restart();
                    have = 0;
                    tagBytes = 0;
                }
                long length = connection.getContentLengthLong();
                if (total < 0 && length > 0) {
                    total = have + length;
                    requireRoom(writing, length);
                }
                try (InputStream input = new BufferedInputStream(connection.getInputStream(), 64 * 1024)) {
                    OutputStream output = writing.stream();
                    if (!resumed) tagBytes = writeTag(input, output, fileName, track);
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (cancelled.getAsBoolean()) return -1;
                        output.write(buffer, 0, read);
                        have += read;
                    }
                }
                if (total >= 0 && have < total) throw new IOException("The file ended at " + have + " of " + total + " bytes");
                return tagBytes + have;
            } catch (IOException ex) {
                if (ex instanceof HlsDownloader.NoSpaceException || DownloadLocation.isFull(ex)
                        || failures >= DownloadHttp.MAX_RETRIES || !DownloadHttp.isTransient(ex)
                        || Thread.currentThread().isInterrupted()) {
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

    /** Fails early, instead of after the whole file was downloaded, when the storage under the file is too small. */
    private static void requireRoom(DownloadLocation.Writing writing, long bytes) throws HlsDownloader.NoSpaceException {
        long free = writing.freeBytes();
        if (free >= 0 && free < bytes + CARD_RESERVE_BYTES) {
            throw new HlsDownloader.NoSpaceException(free / (1024 * 1024) + " MB free, " + bytes / (1024 * 1024) + " MB needed");
        }
    }

    /**
     * Writes the title and artist in front of an mp3 that has none. The tags of any other kind of file would have to be
     * put inside it, which is not possible while it arrives: such a file is saved as it comes.
     *
     * @return How many bytes of tag were written.
     */
    private static long writeTag(InputStream input, OutputStream output, String fileName, TrackSource track) throws IOException {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".mp3")) return 0;
        byte[] header = FileTags.id3Header(track.title, track.artist);
        if (header == null) return 0;
        // A tag the uploader wrote is theirs: it stays.
        input.mark(3);
        byte[] head = new byte[3];
        int read = 0;
        while (read < head.length) {
            int more = input.read(head, read, head.length - read);
            if (more < 0) break;
            read += more;
        }
        input.reset();
        if (read == 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return 0;
        output.write(header);
        return header.length;
    }
}
