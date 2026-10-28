package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.content.Context;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import app.revanced.extension.soundcloud.download.TranscriptionAudio;

/**
 * Finds the sound of a track to listen to, as a file the decoder can open. Three places, in this order:
 * <ol>
 * <li>an imported file on the phone (the urn "local_..." holds its path);</li>
 * <li>a track the user downloaded with Arsound;</li>
 * <li>the stream, fetched into a temporary file that is deleted after the decoding. Only a whole, unprotected
 * track: {@link TranscriptionAudio} refuses previews, subscription tracks and every DRM stream first.</li>
 * </ol>
 * Called by {@link TranscriptionJob}.
 */
final class TrackAudio {
    /** The track cannot be listened to (protected, preview, not found); not a failure to try again. */
    static final class Unavailable extends IOException {
        Unavailable(String message) {
            super(message);
        }
    }

    /** A file to decode, and whether it is a temporary one that goes away with {@link #close}. */
    static final class Prepared implements Closeable {
        final File file;
        private final boolean temporary;

        Prepared(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }

        @Override
        public void close() {
            //noinspection ResultOfMethodCallIgnored
            if (temporary) file.delete();
        }
    }

    private static final long MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024;
    private static final long STALE_MS = 30L * 60 * 1000;
    private static final String LOCAL_PREFIX = "local_";

    private TrackAudio() {
    }

    /**
     * The part of a urn this code looks the track up by: the numeric SoundCloud id ("soundcloud:tracks:123" or a bare
     * "123"), or "local_..." for an imported file ("sc-local:tracks:local_..." or bare). Null for anything else
     * (playlists, users, ads), which cannot be listened to.
     */
    static String trackKey(String urn) {
        if (urn == null) return null;
        String key = urn;
        int colon = key.lastIndexOf(':');
        if (colon >= 0) {
            String kind = key.substring(0, colon);
            if (!kind.equals("soundcloud:tracks") && !kind.equals("sc-local:tracks")) return null;
            key = key.substring(colon + 1);
        }
        if (key.isEmpty()) return null;
        if (key.startsWith(LOCAL_PREFIX)) return key.length() > LOCAL_PREFIX.length() ? key : null;
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) return null;
        }
        return key;
    }

    /** Whether the urn names something this code can look for: a SoundCloud track, or an imported file. */
    static boolean isCandidate(String urn) {
        return trackKey(urn) != null;
    }

    static Prepared prepare(Context context, String trackUrn, ResumableDownload.Cancel cancel) throws IOException {
        sweepStale(context);
        String urn = trackKey(trackUrn);
        if (urn == null) throw new Unavailable("Not a track: " + trackUrn);
        if (urn.startsWith(LOCAL_PREFIX)) {
            File file = importedFile(urn);
            if (file == null) throw new Unavailable("The imported file is not there");
            return new Prepared(file, false);
        }
        try (InputStream downloaded = TranscriptionAudio.openDownloaded(context, urn)) {
            if (downloaded != null) {
                File temp = tempFile(context);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = downloaded.read(buffer)) > 0) {
                        if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                        output.write(buffer, 0, count);
                    }
                } catch (IOException ex) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    throw ex;
                }
                return new Prepared(temp, true);
            }
        }
        return fetchStream(context, urn, cancel);
    }

    private static Prepared fetchStream(Context context, String urn, ResumableDownload.Cancel cancel) throws IOException {
        TranscriptionAudio.Stream stream;
        try {
            stream = TranscriptionAudio.resolve(urn);
        } catch (TranscriptionAudio.Refused ex) {
            throw new Unavailable(ex.getMessage());
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Could not find out how the track is served: " + ex.getMessage(), ex);
        }
        File temp = tempFile(context);
        boolean ok = false;
        try {
            if (stream.hls) {
                try {
                    HlsFetch.download(stream.url, temp, MAX_DOWNLOAD_BYTES, HttpBytes.small(cancel), cancel);
                } catch (HlsPlaylist.Unsupported ex) {
                    throw new Unavailable(ex.getMessage());
                }
            } else {
                HttpBytes.copyTo(stream.url, temp, MAX_DOWNLOAD_BYTES, cancel);
            }
            ok = true;
            return new Prepared(temp, true);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (!ok) temp.delete();
        }
    }

    /** The file an imported track's urn names: its path, in Base64 after the "local_" prefix. */
    static File importedFile(String urn) {
        String encoded = urn.substring(LOCAL_PREFIX.length());
        byte[] path = null;
        try {
            path = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException first) {
            try {
                path = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException second) {
                // Not a path at all: there is no file.
                return null;
            }
        }
        if (path == null) return null;
        File file = new File(new String(path, StandardCharsets.UTF_8));
        return file.isFile() && file.canRead() ? file : null;
    }

    private static File tempFile(Context context) throws IOException {
        File folder = new File(context.getCacheDir(), "arsound");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No cache folder");
        return new File(folder, "asr-" + System.nanoTime() + ".tmp");
    }

    /** Temporary files left by a process that was killed in the middle of a job. */
    private static void sweepStale(Context context) {
        File[] files = new File(context.getCacheDir(), "arsound").listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.getName().startsWith("asr-") && now - file.lastModified() > STALE_MS) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}
