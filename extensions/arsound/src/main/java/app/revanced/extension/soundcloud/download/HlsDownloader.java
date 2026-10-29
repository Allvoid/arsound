package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Saves a track that SoundCloud only offers as an HLS playlist.
 * <p>
 * The player already plays these streams, so the same steps are done by hand: the playlist is read,
 * the AES-128 key is fetched, and every segment is downloaded and decrypted into one file in
 * the download folder (Music/Arsound unless the user picked another). SoundCloud sends fragmented MP4, which is playable as soon as its {@code #EXT-X-MAP}
 * headers are in place; a transport stream is remuxed into {@code .m4a} instead. Tracks that need a
 * subscription never reach this class.
 */
public final class HlsDownloader {
    /** How many segments are downloaded at the same time. */
    private static final int PARALLEL_SEGMENTS = 4;
    /** How many segments may be downloaded but not yet written, which caps the memory of one track. */
    private static final int SEGMENTS_AHEAD = PARALLEL_SEGMENTS * 2;
    /** The sequence number of an {@code #EXT-X-MAP} segment, which is never encrypted. */
    private static final int INITIALISATION_SEGMENT = -1;
    /** A master may point to another master before reaching the audio playlist. */
    private static final int MAX_PLAYLIST_DEPTH = 4;
    private static final Pattern KEY_ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    /** The start of the names of the cache files of an assembly. */
    static final String FILE_PREFIX = "arsound-hls-";
    /** Files of an assembly older than this were left by a process that was killed. */
    private static final long STALE_FILE_MS = 30 * 60 * 1000L;
    private static final AtomicBoolean cacheSwept = new AtomicBoolean();
    /** The room that must be left on the phone after an assembly started: the system needs some to keep running. */
    static final long MIN_FREE_BYTES = 50L * 1024 * 1024;

    private HlsDownloader() {
    }

    /** The track was deleted while it was assembled, so the rest of the segments are not asked for. */
    private static final class CancelledException extends IOException {
        CancelledException() {
            super("The assembly was cancelled");
        }
    }

    /** The phone has no room for the track. It leaves {@link #download}, so the caller can tell the user why. */
    static final class NoSpaceException extends IOException {
        NoSpaceException(String message) {
            super(message);
        }
    }

    /** One {@code #EXT-X-KEY} line: how the segments after it are encrypted. */
    private static final class Key {
        String method = "NONE";
        String uri;
        String keyFormat = "identity";
        byte[] iv;
    }

    private static final class Segment {
        final String url;
        final Key key;
        /** A long: the number of a playlist that has been running for a while does not fit an int. */
        final long sequence;

        Segment(String url, Key key, long sequence) {
            this.url = url;
            this.key = key;
            this.sequence = sequence;
        }
    }

    /**
     * Downloads, decrypts and packs an HLS track. Runs on the calling background thread.
     *
     * @param track     The {@code .m3u8} URL resolved from the track metadata, the mime type of the transcoding (used
     *                  when the stream cannot be remuxed), and the title and artist that name and tag the file.
     * @param location  Where the file is saved, read once when the download started.
     * @param cancelled Asked between segments: once true, the rest are not downloaded and the result is null.
     * @return The saved file with its location and media store row, or null if the track could not be saved.
     * @throws NoSpaceException If the phone has no room for the track.
     */
    static DownloadIndex.Ref download(Context context, String trackId, TrackSource track, DownloadLocation location,
                                      BooleanSupplier cancelled) throws NoSpaceException {
        sweepStaleFiles(context);
        File cache = context.getCacheDir();
        File transportStream = new File(cache, FILE_PREFIX + trackId + ".ts");
        File packed = new File(cache, FILE_PREFIX + trackId + ".m4a");
        File stripped = new File(cache, FILE_PREFIX + trackId + ".mp4");
        try {
            requireFreeSpace(cache, MIN_FREE_BYTES);
            List<Segment> segments = parsePlaylist(track.url);
            boolean hasAudio = false;
            for (Segment segment : segments) hasAudio |= segment.sequence != INITIALISATION_SEGMENT;
            if (!hasAudio) {
                // Only the headers of a fragmented MP4 would be saved as a silent "complete" file.
                Logger.printInfo(() -> "HLS playlist of " + trackId + " has no segments");
                return null;
            }
            String method = segments.get(segments.size() - 1).key.method;
            Logger.printInfo(() -> "HLS track " + trackId + ": " + segments.size() + " segments, key " + method);

            writeDecrypted(segments, transportStream, cancelled);
            // Packing and publishing make one more copy of the track each, next to the decrypted stream.
            requireFreeSpace(cache, 2 * transportStream.length() + MIN_FREE_BYTES);

            // "Artist - Title" when the track's data is known, the id otherwise.
            String base = track.fileBase != null ? track.fileBase : "soundcloud-" + trackId;
            String fileName;
            File source;
            if (isFragmentedMp4(transportStream)) {
                // Every segment brings its own index, and the first one covers only its own ten seconds: players that
                // trust it show a track of ten seconds and stop there. A plain MP4 has the whole length in its header.
                if (remux(transportStream, packed)) {
                    source = packed;
                } else if (dropSegmentIndexes(transportStream, stripped)) {
                    // At least without the false length: the track plays through, only without a length.
                    source = stripped;
                    Logger.printInfo(() -> "HLS track " + trackId + " saved as fragments without their indexes");
                } else {
                    source = transportStream;
                }
                fileName = base + ".m4a";
            } else if (remux(transportStream, packed)) {
                source = packed;
                fileName = base + ".m4a";
            } else {
                // Not a transport stream the system can read: save the decrypted bytes as they are.
                source = transportStream;
                fileName = base + extensionFor(track.mimeType);
                Logger.printInfo(() -> "HLS track " + trackId + " saved without remuxing");
            }

            if (cancelled.getAsBoolean()) throw new CancelledException();
            // Another track may already have this name (two uploads of one song): the id tells them apart.
            return publish(context, location, source, DownloadTrackPatch.freeFileName(location, fileName, trackId),
                    track.title, track.artist);
        } catch (CancelledException ex) {
            Logger.printInfo(() -> "HLS assembly of " + trackId + " was cancelled");
            return null;
        } catch (NoSpaceException ex) {
            Logger.printInfo(() -> "HLS track " + trackId + " has no room: " + ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            // A write that failed on a full storage says only "ENOSPC" in a message that differs between systems.
            if (cache.getUsableSpace() < MIN_FREE_BYTES / 2) {
                NoSpaceException noSpace = new NoSpaceException("The storage ran full: " + ex);
                Logger.printInfo(() -> "HLS track " + trackId + " has no room: " + noSpace.getMessage());
                throw noSpace;
            }
            Logger.printException(() -> "HLS download failure for " + trackId, ex);
            return null;
        } finally {
            delete(transportStream);
            delete(packed);
            delete(stripped);
        }
    }

    /**
     * Copies a fragmented MP4 without its {@code styp} and {@code sidx} boxes: each segment of an HLS track carries
     * its own, and a player that reads only the first takes the track for one segment long.
     *
     * @return True if the copy was made.
     */
    private static boolean dropSegmentIndexes(File source, File target) {
        try (java.io.DataInputStream input = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(source), 64 * 1024));
             java.io.BufferedOutputStream output = new java.io.BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                byte[] header = new byte[16];
                int first = input.read(header, 0, 8);
                if (first < 0) return true;
                if (first < 8) input.readFully(header, first, 8 - first);
                long size = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16) | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                int headerLength = 8;
                if (size == 1) {
                    input.readFully(header, 8, 8);
                    size = 0;
                    for (int i = 8; i < 16; i++) size = (size << 8) | (header[i] & 0xFF);
                    headerLength = 16;
                }
                boolean drop = "styp".equals(type) || "sidx".equals(type);
                if (!drop) output.write(header, 0, headerLength);
                // A size of 0 means "until the end of the file".
                long left = size == 0 ? Long.MAX_VALUE : size - headerLength;
                if (left < 0) return false;
                while (left > 0) {
                    int want = (int) Math.min(buffer.length, left);
                    int read = input.read(buffer, 0, want);
                    if (read < 0) {
                        if (size == 0) return true;
                        return false;
                    }
                    if (!drop) output.write(buffer, 0, read);
                    left -= read;
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not drop the segment indexes: " + ex);
            return false;
        }
    }

    static void delete(File file) {
        if (file.exists() && !file.delete()) Logger.printInfo(() -> "Could not delete " + file);
    }

    static void requireFreeSpace(File folder, long bytes) throws NoSpaceException {
        long free = folder.getUsableSpace();
        if (free < bytes) {
            throw new NoSpaceException(free / (1024 * 1024) + " MB free, " + bytes / (1024 * 1024) + " MB needed");
        }
    }

    /**
     * Deletes the files of assemblies that were cut short when the system killed the process, which skips the
     * clean-up in {@link #download}. Done once per process, before its first assembly; the age keeps the files of
     * an assembly running next to it safe.
     */
    static void sweepStaleFiles(Context context) {
        if (!cacheSwept.compareAndSet(false, true)) return;
        try {
            File[] stale = context.getCacheDir().listFiles((directory, name) -> name.startsWith(FILE_PREFIX));
            if (stale == null) return;
            long now = System.currentTimeMillis();
            for (File file : stale) {
                if (now - file.lastModified() > STALE_FILE_MS) delete(file);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not sweep the old assembly files: " + ex);
        }
    }

    /**
     * Whether the playlist is locked by FairPlay or Widevine. Such a stream hands out its key only to
     * a licence server inside a player, so it is left alone.
     */
    static boolean isDrmProtected(String playlistUrl) throws IOException {
        try {
            return isDrmProtected(playlistUrl, 0);
        } catch (RegionGuard.BlockedException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Could not inspect HLS playlist " + playlistUrl, ex);
        }
    }

    private static boolean isDrmProtected(String playlistUrl, int depth) throws Exception {
        if (depth >= MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
        String playlist = DownloadHttp.withRetry(() -> DownloadHttp.readText(playlistUrl));
        String variant = null;
        long variantBandwidth = -1;
        long pendingBandwidth = -1;
        boolean nextLineIsVariant = false;
        boolean drmSeen = false;
        for (String rawLine : playlist.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (nextLineIsVariant && !line.startsWith("#")) {
                    if (pendingBandwidth > variantBandwidth) {
                        variant = absolute(playlistUrl, line);
                        variantBandwidth = pendingBandwidth;
                    }
                    pendingBandwidth = -1;
                    nextLineIsVariant = false;
                    continue;
                }
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    pendingBandwidth = 0;
                    Matcher attributes = KEY_ATTRIBUTE.matcher(line.substring("#EXT-X-STREAM-INF:".length()));
                    while (attributes.find()) {
                        if ("BANDWIDTH".equals(attributes.group(1))) {
                            try { pendingBandwidth = Long.parseLong(attributes.group(2).replace("\"", "").trim()); }
                            catch (NumberFormatException ignored) { pendingBandwidth = 0; }
                        }
                    }
                    nextLineIsVariant = true;
                    continue;
                }
                if (!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) continue;
                int separator = line.indexOf(':');
                Key key = parseKey(line.substring(separator + 1), playlistUrl);
                // A key the app can fetch itself is an https link; DRM keys use skd:// or a key format of their own.
                boolean plainKey = (key.uri == null || key.uri.startsWith("http")) && "identity".equals(key.keyFormat);
                if (!plainKey || key.method.startsWith("SAMPLE-AES")) {
                     drmSeen = true;
                     Logger.printInfo(() -> "DRM key found: method=" + key.method + " format=" + key.keyFormat + " uri=" + key.uri);
                }
        }
        // DRM is detected if any key requires a license server (not identity/HTTP)
        if (drmSeen) {
             Logger.printInfo(() -> "HLS playlist " + playlistUrl + " is DRM protected");
         }
         return drmSeen || (variant != null && isDrmProtected(variant, depth + 1));
    }

    // region playlist

    private static List<Segment> parsePlaylist(String playlistUrl) throws Exception {
        return parsePlaylist(playlistUrl, 0);
    }

    private static List<Segment> parsePlaylist(String playlistUrl, int depth) throws Exception {
        if (depth >= MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
        String playlist = DownloadHttp.withRetry(() -> DownloadHttp.readText(playlistUrl));
        List<Segment> segments = new ArrayList<>();
        Key key = new Key();
        long sequence = 0;
        String variant = null;
        long variantBandwidth = -1;
        long pendingBandwidth = -1;

        for (String rawLine : playlist.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Matcher attributes = KEY_ATTRIBUTE.matcher(line.substring("#EXT-X-STREAM-INF:".length()));
                pendingBandwidth = -1;
                while (attributes.find()) {
                    if ("BANDWIDTH".equals(attributes.group(1))) {
                        try { pendingBandwidth = Long.parseLong(attributes.group(2).replace("\"", "").trim()); }
                        catch (NumberFormatException ignored) { pendingBandwidth = 0; }
                    }
                }
            } else if (!line.startsWith("#") && pendingBandwidth >= 0) {
                if (pendingBandwidth > variantBandwidth) {
                    variant = absolute(playlistUrl, line);
                    variantBandwidth = pendingBandwidth;
                }
                pendingBandwidth = -1;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                String number = line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim();
                try {
                    sequence = Long.parseLong(number);
                } catch (NumberFormatException ex) {
                    // The default IV of the segments is made from this number: guessing it would give a file of noise.
                    throw new IOException("The playlist has an unreadable EXT-X-MEDIA-SEQUENCE: " + number);
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                key = parseKey(line.substring("#EXT-X-KEY:".length()), playlistUrl);
                requireSupportedKey(key);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                // Fragmented MP4 keeps its headers in a separate first segment; without it there is no file.
                String uri = parseMapUri(line.substring("#EXT-X-MAP:".length()), playlistUrl);
                if (uri != null) segments.add(new Segment(uri, key, INITIALISATION_SEGMENT));
            } else if (!line.startsWith("#")) {
                segments.add(new Segment(absolute(playlistUrl, line), key, sequence++));
            }
        }
        if (variant != null) return parsePlaylist(variant, depth + 1);
        return segments;
    }

    /** Refuses DRM and ciphers this assembler cannot handle, even if source inspection was inconclusive. */
    private static void requireSupportedKey(Key key) throws IOException {
        if ("NONE".equals(key.method)) return;
        // FairPlay/Widevine hand their key out only to a licence server behind a CDM, so this assembler cannot
        // decrypt them: such a track is downloaded through SoundCloud's native offline path instead, not here.
        if (!"identity".equals(key.keyFormat) || key.method.startsWith("SAMPLE-AES")) {
            throw new IOException("DRM-protected HLS (" + key.method + "/" + key.keyFormat + ") is not handled here");
        }
        if (!"AES-128".equals(key.method) && !"AES-CTR".equals(key.method)) {
            throw new IOException("Unsupported HLS encryption method: " + key.method);
        }
        if (key.uri == null || !key.uri.startsWith("http")) {
            throw new IOException("The HLS encryption key is not available as a regular HTTP resource");
        }
    }

    private static String parseMapUri(String attributes, String playlistUrl) {
        Matcher matcher = KEY_ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if ("URI".equals(matcher.group(1))) return absolute(playlistUrl, matcher.group(2).replace("\"", "").trim());
        }
        return null;
    }

    private static Key parseKey(String attributes, String playlistUrl) {
        Key key = new Key();
        Matcher matcher = KEY_ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            String value = matcher.group(2).replace("\"", "").trim();
            switch (matcher.group(1)) {
                case "KEYFORMAT":
                    key.keyFormat = value;
                    break;
                case "METHOD":
                    key.method = value;
                    break;
                case "URI":
                    key.uri = absolute(playlistUrl, value);
                    break;
                case "IV":
                    key.iv = parseHex(value);
                    break;
            }
        }
        return key;
    }

    private static byte[] parseHex(String value) {
        String digits = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String absolute(String base, String reference) {
        try {
            return new URL(new URL(base), reference).toString();
        } catch (Exception ex) {
            return reference;
        }
    }

    // endregion

    // region segments

    /**
     * Downloads every segment, decrypts it and appends it to one file.
     *
     * @throws CancelledException If {@code cancelled} turned true: the segments already on the way finish, no new
     *                            ones are asked for.
     */
    private static void writeDecrypted(List<Segment> segments, File target, BooleanSupplier cancelled) throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_SEGMENTS);
        // Kept for this track only: it is gone with the assembly instead of piling up for the life of the process.
        Map<String, byte[]> keys = new java.util.concurrent.ConcurrentHashMap<>();
        try (FileOutputStream output = new FileOutputStream(target)) {
            // Segments are fetched in parallel, but written in their playlist order. Only a few are
            // submitted ahead of the one being written, so a slow first segment cannot pile up the rest in memory.
            java.util.ArrayDeque<java.util.concurrent.Future<byte[]>> pending = new java.util.ArrayDeque<>();
            int next = 0;
            while (next < segments.size() || !pending.isEmpty()) {
                if (cancelled.getAsBoolean()) throw new CancelledException();
                while (next < segments.size() && pending.size() < SEGMENTS_AHEAD) {
                    Segment segment = segments.get(next++);
                    pending.add(pool.submit(() -> decrypt(segment, keys)));
                }
                output.write(pending.poll().get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] decrypt(Segment segment, Map<String, byte[]> keys) throws Exception {
        // Only the download is repeated; a failure of the key or the cipher below would come out the same again.
        byte[] data = DownloadHttp.withRetry(() -> DownloadHttp.readBytes(segment.url));
        Key key = segment.key;
        if ("NONE".equals(key.method)) return data;
        // The headers of a fragmented MP4 come in the clear, and decrypting them would destroy them.
        if (segment.sequence == INITIALISATION_SEGMENT) return data;
        // Saving the bytes of a track that says it is encrypted, without a key, would give a file that never plays.
        if (key.uri == null) throw new IOException("The playlist declares " + key.method + " but gives no key");

        byte[] secret = keyBytes(key.uri, keys);
        byte[] iv = key.iv != null ? key.iv : sequenceIv(segment.sequence);
        // AES-128 in CBC pads the last block of every segment; in CTR nothing is padded.
        boolean counterMode = key.method.contains("CTR");
        Cipher cipher = Cipher.getInstance(counterMode ? "AES/CTR/NoPadding" : "AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(data);
        return counterMode ? plain : stripPadding(plain);
    }

    /**
     * CBC segments end with PKCS#7 padding, which is removed by hand: the cipher cannot do it,
     * because a segment that was not padded at all would then fail.
     */
    private static byte[] stripPadding(byte[] plain) {
        if (plain.length == 0) return plain;
        int padding = plain[plain.length - 1] & 0xFF;
        if (padding < 1 || padding > 16 || padding > plain.length) return plain;
        for (int i = plain.length - padding; i < plain.length; i++) {
            if ((plain[i] & 0xFF) != padding) return plain;
        }
        byte[] stripped = new byte[plain.length - padding];
        System.arraycopy(plain, 0, stripped, 0, stripped.length);
        return stripped;
    }

    /** Without an IV in the playlist, HLS uses the sequence number of the segment. */
    private static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
        return iv;
    }

    /** One playlist uses one key for all of its segments, so it is fetched once and kept in {@code keys}. */
    private static byte[] keyBytes(String uri, Map<String, byte[]> keys) throws Exception {
        byte[] cached = keys.get(uri);
        if (cached != null) return cached;
        byte[] key = DownloadHttp.withRetry(() -> DownloadHttp.readBytes(uri));
        if (key.length != 16) throw new IOException("The AES key is " + key.length + " bytes, expected 16");
        keys.put(uri, key);
        return key;
    }

    // endregion

    // region packing

    /**
     * Copies the audio track of the decrypted transport stream into an MP4 container.
     *
     * @return True if the file was packed.
     */
    private static boolean remux(File source, File target) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(source.getPath());

            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = candidate;
                    break;
                }
            }
            if (audioTrack < 0) return false;

            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(target.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastTime = -1;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long time = extractor.getSampleTime();
                // The transport stream may repeat its clock; keeping the samples in order avoids a broken file.
                if (time <= lastTime) time = lastTime + 1;
                lastTime = time;

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = time;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outputTrack, buffer, info);
                extractor.advance();
            }
            return lastTime >= 0;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not remux " + source.getName() + ": " + ex);
            return false;
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ignored) {
                    // Nothing was written; the caller falls back to the raw stream.
                }
                muxer.release();
            }
        }
    }

    /** Whether the stream is MP4 in fragments: such a file starts with an {@code ftyp} or {@code styp} box. */
    private static boolean isFragmentedMp4(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] header = new byte[8];
            if (input.read(header) != header.length) return false;
            String box = new String(header, 4, 4, StandardCharsets.US_ASCII);
            return "ftyp".equals(box) || "styp".equals(box);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return ".aac";
        if (mimeType.contains("mpeg")) return ".mp3";
        if (mimeType.contains("opus") || mimeType.contains("ogg")) return ".opus";
        return ".aac";
    }

    /**
     * Moves the packed file into the download location, next to the files of the download manager.
     * Since Android 10 an app cannot write into Music directly, so the media store creates the file; a folder the
     * user picked is written through the document provider.
     *
     * @return The saved file. The name differs from the one asked for when that was taken, for instance by a file of
     * an earlier install that this one cannot see or remove: the media store then names the new file "name (1)", and
     * the track has to remember that name.
     */
    static DownloadIndex.Ref publish(Context context, DownloadLocation location, File packed, String fileName,
                                     String title, String artist) throws Exception {
        DownloadLocation.Saved saved = location.save(context, packed, fileName, FileNames.mimeTypeOf(fileName), title, artist);
        if (saved.uri != null) MediaTags.tag(context, saved.uri, title, artist);
        DownloadIndex.Ref file = new DownloadIndex.Ref(location.id(), saved.name, saved.uri == null ? null : saved.uri.toString());
        DownloadIndex.noteSaved(context, file, packed.length());
        return file;
    }

    // endregion
}






