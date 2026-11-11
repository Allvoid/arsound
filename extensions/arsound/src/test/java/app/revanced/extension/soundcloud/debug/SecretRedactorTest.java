package app.revanced.extension.soundcloud.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class SecretRedactorTest {
    @Test
    public void callbackQueryIsRemovedButTheAddressStays() {
        assertEquals("Opened sc://auth?<redacted> in the app",
                SecretRedactor.redact("Opened sc://auth?code=abc123&state=xyz in the app"));
    }

    @Test
    public void webCallbackQueryIsRemoved() {
        String line = SecretRedactor.redact("GET https://soundcloud.com/auth_mobile_callback?code=SECRETCODE&x=1 failed");
        assertEquals("GET https://soundcloud.com/auth_mobile_callback?<redacted> failed", line);
    }

    @Test
    public void callbackFragmentIsRemoved() {
        String line = SecretRedactor.redact("sc://auth#access_token=abc");
        assertFalse(line.contains("abc"));
    }

    @Test
    public void tokenParametersAreMasked() {
        assertEquals("url?client_id=1&access_token=<redacted>&limit=5",
                SecretRedactor.redact("url?client_id=1&access_token=AbC-123&limit=5"));
    }

    @Test
    public void jsonTokensAreMasked() {
        assertEquals("{\"access_token\":\"<redacted>\",\"expires_in\":3600}",
                SecretRedactor.redact("{\"access_token\":\"2-293-abc\",\"expires_in\":3600}"));
    }

    @Test
    public void authorizationHeadersAreMasked() {
        assertEquals("Authorization: OAuth <redacted>", SecretRedactor.redact("Authorization: OAuth 2-293456-77-abcdef"));
        assertEquals("Bearer <redacted>", SecretRedactor.redact("Bearer eyJhbGciOi1JIUzI1NiJ9"));
    }

    @Test
    public void signedAudioAddressesLoseTheSignature() {
        assertEquals("GET https://cf-hls-media.sndcdn.com/media/1/2/a.128.mp3?Policy=<redacted>&Signature=<redacted>"
                        + "&Key-Pair-Id=<redacted> failed",
                SecretRedactor.redact("GET https://cf-hls-media.sndcdn.com/media/1/2/a.128.mp3?Policy=eyJTdGF0ZW1lbnQi"
                        + "&Signature=AbC~dEf-123__&Key-Pair-Id=APKAI6TU7MMXM5DG6EPQ failed"));
    }

    @Test
    public void trackAuthorizationIsMasked() {
        assertEquals("stream?client_id=1&track_authorization=<redacted>",
                SecretRedactor.redact("stream?client_id=1&track_authorization=eyJ0eXAiOiJKV1QifQ.abc.def"));
    }

    @Test
    public void cookiesAreMaskedToTheEndOfTheLine() {
        assertEquals("Cookie: <redacted>\nnext line", SecretRedactor.redact("Cookie: SID=abc; HSID=def\nnext line"));
        assertEquals("set-cookie: <redacted>", SecretRedactor.redact("set-cookie: sc_session=1-2-3; Path=/"));
    }

    @Test
    public void proofOfOriginAndSessionsAreMasked() {
        assertEquals("videoplayback?n=1&pot=<redacted>&c=WEB", SecretRedactor.redact("videoplayback?n=1&pot=MnQabc_-12&c=WEB"));
        assertEquals("{\"po_token\":\"<redacted>\"}", SecretRedactor.redact("{\"po_token\":\"MnQabc\"}"));
    }

    @Test
    public void ordinaryTextIsKept() {
        String line = "OAuth authentication failed, code=404 for https://api.soundcloud.com/me?limit=5";
        assertEquals(line, SecretRedactor.redact(line));
    }
}
