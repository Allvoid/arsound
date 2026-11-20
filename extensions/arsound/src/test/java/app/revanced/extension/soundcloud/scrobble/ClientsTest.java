package app.revanced.extension.soundcloud.scrobble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ClientsTest {
    private static final ScrobbleListen LISTEN = new ScrobbleListen("Some Artist", "Some Song", 1_700_000_000L, 215_000);

    @Test
    public void md5MatchesTheKnownValues() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ListenQueue.Md5.hex(""));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ListenQueue.Md5.hex("abc"));
    }

    @Test
    public void listenBrainzSingleCarriesTheStartTime() throws Exception {
        JSONObject body = new JSONObject(ListenBrainzClient.body("single", LISTEN));
        assertEquals("single", body.getString("listen_type"));
        JSONObject one = body.getJSONArray("payload").getJSONObject(0);
        assertEquals(1_700_000_000L, one.getLong("listened_at"));
        JSONObject metadata = one.getJSONObject("track_metadata");
        assertEquals("Some Artist", metadata.getString("artist_name"));
        assertEquals("Some Song", metadata.getString("track_name"));
        assertEquals(215_000, metadata.getJSONObject("additional_info").getLong("duration_ms"));
    }

    @Test
    public void listenBrainzPlayingNowCarriesNoTime() throws Exception {
        JSONObject body = new JSONObject(ListenBrainzClient.body("playing_now", LISTEN));
        assertFalse(body.getJSONArray("payload").getJSONObject(0).has("listened_at"));
    }

    @Test
    public void listenBrainzLeavesOutAnUnknownLength() throws Exception {
        ScrobbleListen unknown = new ScrobbleListen("A", "B", 1_700_000_000L, 0);
        JSONObject info = new JSONObject(ListenBrainzClient.body("single", unknown))
                .getJSONArray("payload").getJSONObject(0).getJSONObject("track_metadata").getJSONObject("additional_info");
        assertFalse(info.has("duration_ms"));
    }

    @Test
    public void listenBrainzAnswersAreJudgedLikeOnTheDesktop() {
        assertEquals(ListenOutcome.REJECTED, ListenBrainzClient.classify(401));
        assertEquals(ListenOutcome.REJECTED, ListenBrainzClient.classify(403));
        assertEquals(ListenOutcome.RETRY, ListenBrainzClient.classify(429));
        assertEquals(ListenOutcome.RETRY, ListenBrainzClient.classify(503));
        assertEquals(ListenOutcome.RETRY, ListenBrainzClient.classify(0));
        assertEquals(ListenOutcome.DROP, ListenBrainzClient.classify(400));
        assertEquals(ListenOutcome.DROP, ListenBrainzClient.classify(422));
    }

    @Test
    public void lastFmSignatureSortsByNameAndSkipsFormat() {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getSession");
        params.put("api_key", "key");
        params.put("token", "tok");
        params.put("format", "json");
        // md5("api_keykeymethodauth.getSessiontokentoksecret")
        assertEquals(ListenQueue.Md5.hex("api_keykeymethodauth.getSessiontokentoksecret"), LastFmClient.signature(params, "secret"));
        params.remove("format");
        assertEquals(LastFmClient.signature(params, "secret"), LastFmClient.signature(params, "secret"));
    }

    @Test
    public void lastFmSignatureUsesTheRawValuesNotTheEncodedOnes() {
        Map<String, String> params = new HashMap<>();
        params.put("artist", "Тест & Co");
        assertEquals(ListenQueue.Md5.hex("artistТест & Cosecret"), LastFmClient.signature(params, "secret"));
    }

    @Test
    public void lastFmFormBodyIsEncodedAndSorted() {
        Map<String, String> params = new HashMap<>();
        params.put("track", "a b&c");
        params.put("artist", "Тест");
        assertEquals("artist=%D0%A2%D0%B5%D1%81%D1%82&track=a%20b%26c", LastFmClient.formBody(params));
    }

    @Test
    public void lastFmScrobbleCarriesTheTimestampAndNowPlayingDoesNot() {
        Map<String, String> scrobble = LastFmClient.listenParams("track.scrobble", "sk1", LISTEN);
        assertEquals("1700000000", scrobble.get("timestamp"));
        assertEquals("215", scrobble.get("duration"));
        assertEquals("sk1", scrobble.get("sk"));
        assertEquals("Some Artist", scrobble.get("artist"));
        assertEquals("Some Song", scrobble.get("track"));
        Map<String, String> now = LastFmClient.listenParams("track.updateNowPlaying", "sk1", LISTEN);
        assertFalse(now.containsKey("timestamp"));
        ScrobbleListen unknown = new ScrobbleListen("A", "B", 1_700_000_000L, 0);
        assertFalse(LastFmClient.listenParams("track.scrobble", "sk1", unknown).containsKey("duration"));
    }

    @Test
    public void lastFmErrorsAreJudgedLikeOnTheDesktop() {
        int base = LastFmClient.ERROR_BASE;
        for (int refused : new int[]{4, 9, 10, 13, 26}) {
            assertEquals("error " + refused, ListenOutcome.REJECTED, LastFmClient.classify(base + refused));
        }
        for (int later : new int[]{8, 11, 16, 29}) {
            assertEquals("error " + later, ListenOutcome.RETRY, LastFmClient.classify(base + later));
        }
        assertEquals(ListenOutcome.DROP, LastFmClient.classify(base + 6));
        assertEquals(ListenOutcome.RETRY, LastFmClient.classify(0));
        assertEquals(ListenOutcome.RETRY, LastFmClient.classify(500));
        assertEquals(ListenOutcome.REJECTED, LastFmClient.classify(403));
        assertEquals(ListenOutcome.DROP, LastFmClient.classify(404));
        assertTrue(LastFmClient.ERROR_BASE > 599);
    }

    @Test
    public void aBlockPageWith403IsNoRefusalOfTheCredential() {
        // A filter, a captive portal or a firewall answers 401/403 with a page of its own: asked again later.
        assertEquals(0, WebCall.serviceStatus(403, false));
        assertEquals(0, WebCall.serviceStatus(401, false));
        assertEquals(ListenOutcome.RETRY, ListenBrainzClient.classify(WebCall.serviceStatus(403, ListenBrainzClient.isServiceError("<html>Blocked</html>"))));
        assertEquals(ListenOutcome.RETRY, LastFmClient.classify(WebCall.serviceStatus(403, false)));
    }

    @Test
    public void aRefusalWrittenByTheServiceStillStopsTheChannel() {
        String body = "{\"code\":401,\"error\":\"Invalid authorization token.\"}";
        assertTrue(ListenBrainzClient.isServiceError(body));
        assertEquals(401, WebCall.serviceStatus(401, ListenBrainzClient.isServiceError(body)));
        assertEquals(ListenOutcome.REJECTED, ListenBrainzClient.classify(WebCall.serviceStatus(401, true)));
        // Other statuses are not touched.
        assertEquals(429, WebCall.serviceStatus(429, false));
        assertEquals(500, WebCall.serviceStatus(500, false));
        assertFalse(ListenBrainzClient.isServiceError(null));
        assertFalse(ListenBrainzClient.isServiceError(""));
    }

    @Test
    public void aTokenWithACharacterThatCannotGoInAHeaderIsRefusedBeforeARequest() {
        assertTrue(ListenBrainzClient.isTokenText("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        assertFalse(ListenBrainzClient.isTokenText("токен"));
        assertFalse(ListenBrainzClient.isTokenText("abc def"));
        assertFalse(ListenBrainzClient.isTokenText("abc\ndef"));
        assertFalse(ListenBrainzClient.isTokenText("abc\u007F"));
        assertFalse(ListenBrainzClient.isTokenText(""));
        assertFalse(ListenBrainzClient.isTokenText(null));
    }
}
