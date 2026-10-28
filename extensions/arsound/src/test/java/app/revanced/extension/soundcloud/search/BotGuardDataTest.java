package app.revanced.extension.soundcloud.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

public class BotGuardDataTest {
    private static final String CHALLENGE = "[\"m1\",[\"script\"],[\"https://x\"],\"hash\",\"prog\",\"gname\",null,\"blob\"]";
    private static final String CHALLENGE_OBJECT = "{\"messageId\":\"m1\",\"interpreterJavascript\":{"
            + "\"privateDoNotAccessOrElseSafeScriptWrappedValue\":\"script\","
            + "\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":\"https://x\"},"
            + "\"interpreterHash\":\"hash\",\"program\":\"prog\",\"globalName\":\"gname\",\"clientExperimentsStateBlob\":\"blob\"}";

    @Test
    public void aPlainChallengeBecomesAScriptObject() throws Exception {
        assertEquals(CHALLENGE_OBJECT, BotGuardData.parseChallengeData("[" + CHALLENGE + "]"));
    }

    @Test
    public void aScrambledChallengeIsDescrambledFirst() throws Exception {
        byte[] plain = CHALLENGE.getBytes(StandardCharsets.UTF_8);
        byte[] scrambled = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) scrambled[i] = (byte) (plain[i] - 97);
        String encoded = Base64.getEncoder().encodeToString(scrambled).replace('+', '-').replace('/', '_').replace('=', '.');
        assertEquals(CHALLENGE_OBJECT, BotGuardData.parseChallengeData("[null,\"" + encoded + "\"]"));
    }

    @Test
    public void missingScriptValuesBecomeNull() throws Exception {
        String raw = "[[\"m\",null,null,\"h\",\"p\",\"g\",null,\"b\"]]";
        String result = BotGuardData.parseChallengeData(raw);
        assertTrue(result.contains("\"privateDoNotAccessOrElseSafeScriptWrappedValue\":null"));
        assertTrue(result.contains("\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":null"));
    }

    @Test
    public void quotesAndNewlinesInValuesAreEscaped() throws Exception {
        String raw = "[[\"a\\\"b\\nc\",null,null,\"h\",\"p\",\"g\",null,\"b\"]]";
        assertTrue(BotGuardData.parseChallengeData(raw).startsWith("{\"messageId\":\"a\\\"b\\nc\","));
    }

    @Test
    public void anIntegrityTokenHasBytesAndALifetime() throws Exception {
        String raw = "[\"AQID\",43200]";
        BotGuardData.IntegrityTokenData data = BotGuardData.parseIntegrityTokenData(raw);
        assertEquals("new Uint8Array([1,2,3])", data.integrityTokenU8Array);
        assertEquals(43200, data.expirationTimeSeconds);
    }

    @Test
    public void identifierBecomesABytesLiteral() {
        assertEquals("new Uint8Array([97,98])", BotGuardData.stringToU8("ab"));
    }

    @Test
    public void commaSeparatedBytesBecomeUrlSafeBase64WithoutPadding() {
        assertEquals("AQID", BotGuardData.u8ToBase64("1,2,3"));
        assertEquals("-_8", BotGuardData.u8ToBase64("251, 255"));
        assertEquals("", BotGuardData.u8ToBase64(""));
    }

    @Test
    public void somethingThatIsNotAnArrayIsRefused() {
        try {
            BotGuardData.parseChallengeData("\"text\"");
            fail();
        } catch (PoTokenWebView.PoTokenException expected) {
            assertEquals("Expected JSON array", expected.getMessage());
        }
    }

    @Test
    public void anUnterminatedArrayIsRefused() {
        try {
            BotGuardData.parseChallengeData("[[\"a\",");
            fail();
        } catch (PoTokenWebView.PoTokenException expected) {
            assertTrue(expected.getMessage().startsWith("Unexpected end") || expected.getMessage().startsWith("Unterminated"));
        }
    }

    @Test
    public void badBase64IsRefused() {
        try {
            BotGuardData.parseIntegrityTokenData("[\"@@@\",1]");
            fail();
        } catch (PoTokenWebView.PoTokenException expected) {
            assertEquals("Cannot base64 decode", expected.getMessage());
        }
    }
}
