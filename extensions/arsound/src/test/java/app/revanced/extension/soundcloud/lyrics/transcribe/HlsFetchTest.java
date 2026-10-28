package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class HlsFetchTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final String BASE = "https://cdn.example.com/track/index.m3u8";
    private static final ResumableDownload.Cancel NEVER = () -> false;

    private static HttpBytes.Fetcher serving(final Map<String, byte[]> files) {
        return url -> {
            byte[] body = files.get(url);
            if (body == null) throw new IOException("HTTP 404 " + url);
            return body;
        };
    }

    private static byte[] text(String playlist) {
        return playlist.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void aPlaylistInTheClearIsConcatenated() throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        files.put(BASE, text("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10,\nseg0.ts\n#EXTINF:10,\nseg1.ts\n#EXT-X-ENDLIST\n"));
        files.put("https://cdn.example.com/track/seg0.ts", new byte[]{1, 2, 3});
        files.put("https://cdn.example.com/track/seg1.ts", new byte[]{4, 5});
        File target = temporary.newFile();
        HlsFetch.download(BASE, target, 1000, serving(files), NEVER);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void anAesPlaylistIsDecryptedWithTheSequenceNumberAsTheIv() throws Exception {
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i + 1);
        byte[] plain0 = "first piece of sound".getBytes(StandardCharsets.UTF_8);
        byte[] plain1 = "second piece".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new HashMap<>();
        files.put(BASE, text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.com/k\"\n"
                + "#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:10,\na.ts\n#EXTINF:10,\nb.ts\n"));
        files.put("https://keys.example.com/k", key);
        files.put("https://cdn.example.com/track/a.ts", encrypt(plain0, key, HlsFetch.sequenceIv(7)));
        files.put("https://cdn.example.com/track/b.ts", encrypt(plain1, key, HlsFetch.sequenceIv(8)));
        File target = temporary.newFile();
        HlsFetch.download(BASE, target, 1000, serving(files), NEVER);
        byte[] expected = new byte[plain0.length + plain1.length];
        System.arraycopy(plain0, 0, expected, 0, plain0.length);
        System.arraycopy(plain1, 0, expected, plain0.length, plain1.length);
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aMasterPlaylistLeadsToTheBestVariantAndAnInitSegmentIsNotDecrypted() throws Exception {
        byte[] key = new byte[16];
        Map<String, byte[]> files = new HashMap<>();
        files.put(BASE, text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=256000\nhigh.m3u8\n"));
        files.put("https://cdn.example.com/track/high.m3u8", text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example.com/k\","
                + "IV=0x000102030405060708090A0B0C0D0E0F\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\nfrag.m4s\n"));
        files.put("https://k.example.com/k", key);
        files.put("https://cdn.example.com/track/init.mp4", new byte[]{9, 9});
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) iv[i] = (byte) i;
        files.put("https://cdn.example.com/track/frag.m4s", encrypt(new byte[]{7, 7, 7}, key, iv));
        File target = temporary.newFile();
        HlsFetch.download(BASE, target, 1000, serving(files), NEVER);
        assertArrayEquals(new byte[]{9, 9, 7, 7, 7}, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aProtectedStreamIsRefusedBeforeAnyPieceIsAsked() throws IOException {
        String[] locked = {
                "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\",KEYFORMAT=\"com.apple.streamingkeydelivery\"",
                "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"https://k.example.com/k\"",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"skd://license\"",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example.com/k\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"http://k.example.com/k\"",
                "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"https://k.example.com/k\"",
                "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\",KEYFORMAT=\"com.apple.streamingkeydelivery\""};
        for (String line : locked) {
            final int[] asked = {0};
            Map<String, byte[]> files = new HashMap<>();
            files.put(BASE, text("#EXTM3U\n" + line + "\n#EXTINF:10,\nseg0.ts\n"));
            HttpBytes.Fetcher counting = url -> {
                asked[0]++;
                return serving(files).get(url);
            };
            try {
                HlsFetch.download(BASE, temporary.newFile(), 1000, counting, NEVER);
                fail("a protected playlist must be refused: " + line);
            } catch (HlsPlaylist.Unsupported expected) {
                assertEquals("only the playlist was asked for: " + line, 1, asked[0]);
            }
        }
    }

    @Test
    public void aTrackBiggerThanTheLimitIsRefused() throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        files.put(BASE, text("#EXTM3U\n#EXTINF:10,\nbig.ts\n"));
        files.put("https://cdn.example.com/track/big.ts", new byte[500]);
        try {
            HlsFetch.download(BASE, temporary.newFile(), 100, serving(files), NEVER);
            fail("expected a refusal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("too big"));
        }
    }

    @Test
    public void paddingIsStrippedOnlyWhenItIsPadding() {
        assertArrayEquals(new byte[]{1, 2}, HlsFetch.stripPadding(new byte[]{1, 2, 2, 2}));
        assertArrayEquals(new byte[]{1, 2, 3, 5}, HlsFetch.stripPadding(new byte[]{1, 2, 3, 5}));
        assertArrayEquals(new byte[0], HlsFetch.stripPadding(new byte[0]));
    }

    @Test
    public void thePlaylistTellsItsVariantOrItsPieces() throws IOException {
        HlsPlaylist master = HlsPlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n", BASE);
        assertEquals("https://cdn.example.com/track/a.m3u8", master.variantUrl);
        assertTrue(master.segments.isEmpty());
        HlsPlaylist media = HlsPlaylist.parse("#EXTM3U\n#EXTINF:1,\nx.ts\n", BASE);
        assertNull(media.variantUrl);
        assertNotNull(media.segments.get(0));
        assertNull(media.segments.get(0).keyUrl);
    }

    private static byte[] encrypt(byte[] plain, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(plain);
    }
}
