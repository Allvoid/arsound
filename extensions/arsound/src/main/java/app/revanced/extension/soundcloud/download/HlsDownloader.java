package app.revanced.extension.soundcloud.download;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.revanced.extension.shared.Logger;

/**
 * Saves a track that SoundCloud only offers as an HLS playlist.
 * <p>
 * The player already plays these streams, so the same steps are done by hand: the playlist is read,
 * the AES-128 key is fetched, and every segment is downloaded and decrypted into one file in
 * Music/Arsound. SoundCloud sends fragmented MP4, which is playable as soon as its {@code #EXT-X-MAP}
 * headers are in place; a transport stream is remuxed into {@code .m4a} instead. Tracks that need a
 * subscription never reach this class.
 */
public final class HlsDownloader {
    /** How many segments are downloaded at the same time. */
    private static final int PARALLEL_SEGMENTS = 4;
    /** The sequence number of an {@code #EXT-X-MAP} segment, which is never encrypted. */
    private static final int INITIALISATION_SEGMENT = -1;
    private static final Pattern KEY_ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    private HlsDownloader() {
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
        final int sequence;

        Segment(String url, Key key, int sequence) {
            this.url = url;
            this.key = key;
            this.sequence = sequence;
        }
    }

    /**
     * Downloads, decrypts and packs an HLS track. Runs on the calling background thread.
     *
     * @param playlistUrl The {@code .m3u8} URL resolved from the track metadata.
     * @param mimeType    The mime type of the transcoding, used when the stream cannot be remuxed.
     * @return The name of the saved file in Music/Arsound, or null if the track could not be saved.
     */
    static String download(Context context, String trackId, String playlistUrl, String mimeType) {
        File transportStream = new File(context.getCacheDir(), "arsound-hls-" + trackId + ".ts");
        File packed = new File(context.getCacheDir(), "arsound-hls-" + trackId + ".m4a");
        try {
            List<Segment> segments = parsePlaylist(playlistUrl);
            if (segments.isEmpty()) {
                Logger.printInfo(() -> "HLS playlist of " + trackId + " has no segments");
                return null;
            }
            String method = segments.get(segments.size() - 1).key.method;
            Logger.printInfo(() -> "HLS track " + trackId + ": " + segments.size() + " segments, key " + method);

            writeDecrypted(segments, transportStream);

            String fileName;
            File source;
            if (isFragmentedMp4(transportStream)) {
                // Fragmented MP4 with its headers is already a playable file; nothing has to be repacked.
                source = transportStream;
                fileName = "soundcloud-" + trackId + ".m4a";
            } else if (remux(transportStream, packed)) {
                source = packed;
                fileName = "soundcloud-" + trackId + ".m4a";
            } else {
                // Not a transport stream the system can read: save the decrypted bytes as they are.
                source = transportStream;
                fileName = "soundcloud-" + trackId + extensionFor(mimeType);
                Logger.printInfo(() -> "HLS track " + trackId + " saved without remuxing");
            }

            publish(context, source, fileName);
            return fileName;
        } catch (Exception ex) {
            Logger.printException(() -> "HLS download failure for " + trackId, ex);
            return null;
        } finally {
            delete(transportStream);
            delete(packed);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) Logger.printInfo(() -> "Could not delete " + file);
    }

    /**
     * Whether the playlist is locked by FairPlay or Widevine. Such a stream hands out its key only to
     * a licence server inside a player, so it is left alone.
     */
    static boolean isDrmProtected(String playlistUrl) {
        try {
            for (String rawLine : readText(playlistUrl).split("\n")) {
                String line = rawLine.trim();
                if (!line.startsWith("#EXT-X-KEY:")) continue;
                Key key = parseKey(line.substring("#EXT-X-KEY:".length()), playlistUrl);
                // A key the app can fetch itself is an https link; DRM keys use skd:// or a key format of their own.
                boolean plainKey = (key.uri == null || key.uri.startsWith("http")) && "identity".equals(key.keyFormat);
                if (!plainKey || key.method.startsWith("SAMPLE-AES")) return true;
            }
            return false;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the playlist of " + playlistUrl + ": " + ex);
            return false;
        }
    }

    // region playlist

    private static List<Segment> parsePlaylist(String playlistUrl) throws Exception {
        String playlist = readText(playlistUrl);
        List<Segment> segments = new ArrayList<>();
        Key key = new Key();
        int sequence = 0;

        for (String rawLine : playlist.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = Integer.parseInt(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
            } else if (line.startsWith("#EXT-X-KEY:")) {
                key = parseKey(line.substring("#EXT-X-KEY:".length()), playlistUrl);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                // Fragmented MP4 keeps its headers in a separate first segment; without it there is no file.
                String uri = parseMapUri(line.substring("#EXT-X-MAP:".length()), playlistUrl);
                if (uri != null) segments.add(new Segment(uri, key, INITIALISATION_SEGMENT));
            } else if (!line.startsWith("#")) {
                segments.add(new Segment(absolute(playlistUrl, line), key, sequence++));
            }
        }
        return segments;
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

    /** Downloads every segment, decrypts it and appends it to one file. */
    private static void writeDecrypted(List<Segment> segments, File target) throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_SEGMENTS);
        try (FileOutputStream output = new FileOutputStream(target)) {
            // Segments are fetched in parallel, but written in their playlist order.
            List<java.util.concurrent.Future<byte[]>> pending = new ArrayList<>();
            for (Segment segment : segments) pending.add(pool.submit(() -> decrypt(segment)));
            for (java.util.concurrent.Future<byte[]> future : pending) output.write(future.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] decrypt(Segment segment) throws Exception {
        byte[] data = readBytes(segment.url);
        Key key = segment.key;
        if (key.uri == null || "NONE".equals(key.method)) return data;
        // The headers of a fragmented MP4 come in the clear, and decrypting them would destroy them.
        if (segment.sequence == INITIALISATION_SEGMENT) return data;

        byte[] secret = keyBytes(key.uri);
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
    private static byte[] sequenceIv(int sequence) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 4; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
        return iv;
    }

    private static final java.util.Map<String, byte[]> keyCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** One playlist uses one key for all of its segments, so it is fetched once. */
    private static byte[] keyBytes(String uri) throws Exception {
        byte[] cached = keyCache.get(uri);
        if (cached != null) return cached;
        byte[] key = readBytes(uri);
        if (key.length != 16) throw new IOException("The AES key is " + key.length + " bytes, expected 16");
        keyCache.put(uri, key);
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
     * Moves the packed file into Music/Arsound, next to the files of the download manager.
     * Since Android 10 an app cannot write there directly, so the media store creates the file.
     */
    private static void publish(Context context, File packed, String fileName) throws Exception {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Arsound");
        File target = new File(folder, fileName);
        if (target.exists() && !target.delete()) Logger.printInfo(() -> "Could not replace " + target);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Music/Arsound could not be created");
            copy(packed, new FileOutputStream(target));
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(fileName));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Arsound");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("The file could not be created in Music/Arsound");
        try {
            OutputStream output = context.getContentResolver().openOutputStream(uri);
            if (output == null) throw new IOException("The file could not be opened");
            copy(packed, output);

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
        } catch (Exception ex) {
            context.getContentResolver().delete(uri, null, null);
            throw ex;
        }
    }

    /** The type has to match the extension, otherwise the media store appends one of its own. */
    private static String mimeTypeOf(String fileName) {
        if (fileName.endsWith(".m4a")) return "audio/mp4";
        if (fileName.endsWith(".aac")) return "audio/aac";
        if (fileName.endsWith(".opus")) return "audio/ogg";
        return "audio/mpeg";
    }

    private static void copy(File source, OutputStream output) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source); OutputStream target = output) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) target.write(buffer, 0, read);
        }
    }

    // endregion

    // region network

    private static byte[] readBytes(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        app.revanced.extension.soundcloud.network.RegionGuard.throwIfBlocked(new URL(url).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for " + url);
        return connection;
    }

    // endregion
}
