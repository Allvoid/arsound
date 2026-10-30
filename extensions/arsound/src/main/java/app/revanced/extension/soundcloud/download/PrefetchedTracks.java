package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.function.BooleanSupplier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Whole copies of streamed tracks fetched before they play, so the next track starts from the phone at once and does
 * not stop on a weak network. They live in the app's cache ({@code cache/arsound-next}), apart from the user's
 * downloads: they are not shown as downloaded, take no room in the download folder, and only the copies of the
 * tracks around the playing one are kept.
 * <p>
 * A track is fetched only when it could be downloaded: a full track SoundCloud serves to anyone, as one file or as
 * an open or AES-128 HLS playlist. Previews, tracks that need a subscription and DRM streams are left to SoundCloud.
 * A copy appears under its final name only when it is complete, so playback never meets half a file.
 */
public final class PrefetchedTracks {
    private static final String FOLDER = "arsound-next";
    private static final String READY = ".ready";
    private static final String USER_AGENT = "Arsound";
    /** A longer track (a DJ set, a podcast) is streamed as usual: its copy would cost a lot of data and room. */
    private static final long MAX_BYTES = 60L * 1024 * 1024;
    /** The same for HLS: segments are about ten seconds, so this is about half an hour. */
    private static final int MAX_SEGMENTS = 180;

    private PrefetchedTracks() {
    }

    private static File folder(Context context) {
        return new File(context.getCacheDir(), FOLDER);
    }

    /** The complete copy of a track, or null. */
    private static File copyOf(Context context, String trackId) {
        File[] files = folder(context).listFiles();
        if (files == null) return null;
        String prefix = trackId + READY;
        for (File file : files) if (file.getName().startsWith(prefix)) return file;
        return null;
    }

    /** The path of the complete copy of a track, for the player; null if there is none. */
    public static String playable(String trackId) {
        Context context = Utils.getContext();
        if (context == null || trackId == null) return null;
        File copy = copyOf(context, trackId);
        return copy == null ? null : copy.getAbsolutePath();
    }

    /**
     * Fetches the whole track into the cache. Blocks: call it on a background thread.
     *
     * @param cancelled Asked while the track arrives: once true, the rest is not fetched.
     * @return True when the copy is ready (also when it was there already); false when the track is not one to
     * fetch ahead, was cancelled or is too long.
     * @throws Exception For a failure of the network or of SoundCloud's answer.
     */
    public static boolean fetch(Context context, String trackId, BooleanSupplier cancelled) throws Exception {
        if (copyOf(context, trackId) != null) return true;
        File folder = folder(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder " + folder);
        HlsDownloader.requireFreeSpace(folder, HlsDownloader.MIN_FREE_BYTES);
        TrackSource source = DownloadTrackPatch.resolveSource(trackId);
        if (cancelled.getAsBoolean()) return false;
        switch (source.status) {
            case READY:
                return source.url != null && fetchFile(folder, trackId, source, cancelled);
            case REQUIRES_HLS_PROCESSING:
                // A protected playlist can come with this status too: the playlist itself is looked at.
                return source.url != null && !HlsDownloader.isDrmProtected(source.url)
                        && assemble(folder, trackId, source, cancelled);
            default:
                Logger.printDebug(() -> "Next track " + trackId + " is not fetched ahead: " + source.status);
                return false;
        }
    }

    private static boolean fetchFile(File folder, String trackId, TrackSource source, BooleanSupplier cancelled)
            throws Exception {
        File part = new File(folder, trackId + ".part");
        try {
            HttpURLConnection connection = DownloadHttp.withRetry(() -> DownloadHttp.open(source.url, 0, USER_AGENT));
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(part)) {
                long length = connection.getContentLengthLong();
                if (length > MAX_BYTES) {
                    Logger.printInfo(() -> "Next track " + trackId + " is too long to fetch ahead: " + length + " bytes");
                    return false;
                }
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (cancelled.getAsBoolean() || (total += read) > MAX_BYTES) return false;
                    output.write(buffer, 0, read);
                }
            } finally {
                connection.disconnect();
            }
            return publish(part, new File(folder, trackId + READY + HlsDownloader.extensionFor(source.mimeType)));
        } finally {
            HlsDownloader.delete(part);
        }
    }

    private static boolean assemble(File folder, String trackId, TrackSource source, BooleanSupplier cancelled)
            throws Exception {
        HlsDownloader.Parts parts = new HlsDownloader.Parts(new File(folder, trackId + ".ts"),
                new File(folder, trackId + ".m4a"), new File(folder, trackId + ".mp4"));
        try {
            HlsDownloader.Packed packed = HlsDownloader.assemble(trackId, source.url, source.mimeType, parts, cancelled,
                    MAX_SEGMENTS);
            return packed != null && !cancelled.getAsBoolean()
                    && publish(packed.file, new File(folder, trackId + READY + packed.extension));
        } catch (HlsDownloader.CancelledException ex) {
            return false;
        } finally {
            parts.delete();
        }
    }

    private static boolean publish(File complete, File target) {
        if (!complete.renameTo(target)) return false;
        Logger.printInfo(() -> "Next track fetched ahead: " + target.getName() + ", " + target.length() / 1024 + " KB");
        return true;
    }

    /** Deletes every copy and unfinished part, except the copies of the given tracks. */
    public static void keepOnly(Context context, Collection<String> trackIds) {
        File[] files = folder(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            int dot = name.indexOf('.');
            String id = dot < 0 ? name : name.substring(0, dot);
            if (!name.contains(READY) || !trackIds.contains(id)) HlsDownloader.delete(file);
        }
    }
}
