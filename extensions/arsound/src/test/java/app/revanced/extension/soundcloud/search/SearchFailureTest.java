package app.revanced.extension.soundcloud.search;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public class SearchFailureTest {
    @Test
    public void aPlainErrorIsOther() {
        assertEquals(SearchFailure.OTHER, SearchFailure.of(new IOException("boom")));
    }

    @Test
    public void aBotCheckIsFoundInsideOtherErrors() {
        Exception wrapped = new Exception("outer", new RuntimeException(new YouTubeAccount.BotCheckException("bot")));
        assertEquals(SearchFailure.BOT_CHECK, SearchFailure.of(wrapped));
    }

    @Test
    public void anAgeRestrictedTrackNeedsASignIn() {
        Exception wrapped = new Exception(new YouTubeAccount.SignInRequiredException("age"));
        assertEquals(SearchFailure.SIGN_IN_REQUIRED, SearchFailure.of(wrapped));
    }

    @Test
    public void aRefusedStreamIsRefused() {
        assertEquals(SearchFailure.REFUSED, SearchFailure.of(new IOException(new OtherSource.RefusedException("HTTP 403"))));
    }

    @Test
    public void theBotCheckWinsOverTheOtherCauses() {
        YouTubeAccount.SignInRequiredException signIn = new YouTubeAccount.SignInRequiredException("age");
        signIn.initCause(new YouTubeAccount.BotCheckException("bot"));
        assertEquals(SearchFailure.BOT_CHECK, SearchFailure.of(new Exception(signIn)));
    }

    @Test
    public void aMissingErrorIsNotARegionBlock() {
        assertEquals(false, SearchFailure.isRegionBlock(null));
        assertEquals(false, SearchFailure.isRegionBlock(new IOException("x")));
    }
}
