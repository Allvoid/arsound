package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fetches a streamed track piece by piece into one temporary file, decrypting the pieces that are locked by plain
 * AES-128, so that the decoder can read it. The file is for listening to once and is deleted afterwards; it is never
 * kept or offered as a download.
 * <p>
 * A playlist that is protected (see {@link HlsPlaylist}) is refused before a single piece is asked for. Used by
 * {@link TrackAudio}.
 */
final class HlsFetch {
    private static final int MAX_NESTING = 3;

    private HlsFetch() {
    }

    /**
     * @param maxBytes The most the pieces may add up to.
     * @throws HlsPlaylist.Unsupported If the playlist is protected or not understood.
     */
    static void download(String playlistUrl, File target, long maxBytes, HttpBytes.Fetcher fetcher,
                         ResumableDownload.Cancel cancel) throws IOException {
        HlsPlaylist playlist = load(playlistUrl, fetcher);
        if (playlist.segments.isEmpty()) throw new IOException("The playlist has no pieces");
        Map<String, byte[]> keys = new HashMap<>();
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(target)) {
            for (HlsPlaylist.Segment segment : playlist.segments) {
                if (cancel.isCancelled()) throw new ResumableDownload.Cancelled();
                byte[] data = fetcher.get(segment.url);
                byte[] plain = segment.keyUrl == null || segment.isInit ? data : decrypt(segment, data, keys, fetcher);
                total += plain.length;
                if (total > maxBytes) throw new IOException("The track is too big");
                output.write(plain);
            }
        }
    }

    private static HlsPlaylist load(String url, HttpBytes.Fetcher fetcher) throws IOException {
        String current = url;
        for (int depth = 0; depth < MAX_NESTING; depth++) {
            HlsPlaylist playlist = HlsPlaylist.parse(new String(fetcher.get(current), StandardCharsets.UTF_8), current);
            if (playlist.variantUrl == null) return playlist;
            current = playlist.variantUrl;
        }
        throw new IOException("The playlist is nested too deep");
    }

    static byte[] decrypt(HlsPlaylist.Segment segment, byte[] data, Map<String, byte[]> keys,
                          HttpBytes.Fetcher fetcher) throws IOException {
        byte[] secret = keys.get(segment.keyUrl);
        if (secret == null) {
            secret = fetcher.get(segment.keyUrl);
            if (secret.length != 16) throw new IOException("The AES key is " + secret.length + " bytes, not 16");
            keys.put(segment.keyUrl, secret);
        }
        byte[] iv = segment.iv != null ? segment.iv : sequenceIv(segment.sequence);
        try {
            Cipher cipher = Cipher.getInstance(segment.counterMode ? "AES/CTR/NoPadding" : "AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(data);
            return segment.counterMode ? plain : stripPadding(plain);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not decrypt a piece", ex);
        }
    }

    /** The last block of a CBC piece ends with PKCS#7 padding; removed by hand so a piece without padding survives. */
    static byte[] stripPadding(byte[] plain) {
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

    /** Without an IV in the playlist, HLS uses the number of the piece. */
    static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
        return iv;
    }
}
