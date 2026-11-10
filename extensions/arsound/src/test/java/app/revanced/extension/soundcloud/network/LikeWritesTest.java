package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LikeWritesTest {
    @Test
    public void onlyTheFourWritesOfLikesToTheServerAreLikeWrites() {
        for (String path : new String[]{"/likes/tracks/create", "/likes/tracks/delete", "/likes/playlists/create", "/likes/playlists/delete"}) {
            assertTrue(path, LikeWrites.isLikeWrite("api-mobile.soundcloud.com", path, "POST"));
        }
        assertFalse(LikeWrites.isLikeWrite("api-mobile.soundcloud.com", "/likes/tracks/create", "GET"));
        assertFalse(LikeWrites.isLikeWrite("api-mobile.soundcloud.com", "/users/1/likes/tracks", "POST"));
        assertFalse(LikeWrites.isLikeWrite("api-mobile.soundcloud.com", "/tracks/likes/create", "POST"));
        assertFalse(LikeWrites.isLikeWrite("example.com", "/likes/tracks/create", "POST"));
        assertFalse(LikeWrites.isLikeWrite("notsoundcloud.com", "/likes/tracks/create", "POST"));
        assertFalse(LikeWrites.isLikeWrite(null, "/likes/tracks/create", "POST"));
        assertFalse(LikeWrites.isLikeWrite("soundcloud.com", null, "POST"));
        assertFalse(LikeWrites.isLikeWrite("soundcloud.com", "/likes/tracks/create", null));
    }

    @Test
    public void onlyAnAnswerInThe400sIsARefusal() {
        assertTrue(LikeWrites.isRefusal(400));
        assertTrue(LikeWrites.isRefusal(403));
        assertTrue(LikeWrites.isRefusal(429));
        assertFalse(LikeWrites.isRefusal(200));
        assertFalse(LikeWrites.isRefusal(201));
        assertFalse(LikeWrites.isRefusal(302));
        assertFalse(LikeWrites.isRefusal(500));
        assertFalse(LikeWrites.isRefusal(503));
    }

    @Test
    public void theLogLineCarriesTheBodyOnlyOfARefusal() {
        assertEquals("Like request /likes/tracks/create answered 201",
                LikeWrites.logLine("/likes/tracks/create", 201, "{\"likes\":[]}"));
        assertEquals("Like request /likes/tracks/create answered 503",
                LikeWrites.logLine("/likes/tracks/create", 503, "busy"));
        assertEquals("Like request /likes/tracks/create answered 403: {\"error\": \"blocked\"}",
                LikeWrites.logLine("/likes/tracks/create", 403, "{\"error\":\n   \"blocked\"}"));
        assertEquals("Like request /likes/tracks/create answered 403",
                LikeWrites.logLine("/likes/tracks/create", 403, null));
        assertEquals("Like request /likes/tracks/create answered 403",
                LikeWrites.logLine("/likes/tracks/create", 403, "   "));
    }

    @Test
    public void aLongBodyIsCutAndLinesAreJoined() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("line ").append(i).append('\n');
        String line = LikeWrites.logLine("/likes/tracks/create", 400, body.toString());
        assertFalse(line.contains("\n"));
        assertTrue(line.endsWith("…"));
        assertEquals("Like request /likes/tracks/create answered 400: ".length() + LikeWrites.BODY_LOG_CHARS + 1, line.length());
    }

    @Test
    public void theToastNamesTheCodeAndTheLanguageFollowsTheFlag() {
        assertTrue(LikeWrites.toast(403, true).contains("403"));
        assertTrue(LikeWrites.toast(403, true).contains("лайк"));
        assertTrue(LikeWrites.toast(403, false).contains("like"));
        assertTrue(LikeWrites.toast(429, false).contains("429"));
        assertTrue(LikeWrites.toast(404, true).contains("404"));
        assertFalse(LikeWrites.toast(404, false).contains("лайк"));
    }
}
