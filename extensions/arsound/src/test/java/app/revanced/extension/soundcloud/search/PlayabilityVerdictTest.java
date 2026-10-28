package app.revanced.extension.soundcloud.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class PlayabilityVerdictTest {
    private static final String SIGN_IN_BOT = "Sign in to confirm you’re not a bot";

    @Test
    public void anOkStatusIsPlayableWhoeverAsks() {
        assertEquals(PlayabilityVerdict.PLAYABLE, PlayabilityVerdict.of(false, "OK", ""));
        assertEquals(PlayabilityVerdict.PLAYABLE, PlayabilityVerdict.of(true, "ok", "anything"));
        assertNull(PlayabilityVerdict.PLAYABLE.failure("OK", ""));
    }

    @Test
    public void aVisitorTakenForABotIsToldSo() {
        assertEquals(PlayabilityVerdict.VISITOR_BOT, PlayabilityVerdict.of(false, "LOGIN_REQUIRED", SIGN_IN_BOT));
        assertEquals(PlayabilityVerdict.VISITOR_BOT, PlayabilityVerdict.of(false, "UNPLAYABLE", "Requests from bots are blocked"));
        IOException failure = PlayabilityVerdict.VISITOR_BOT.failure("LOGIN_REQUIRED", SIGN_IN_BOT);
        assertTrue(failure instanceof YouTubeAccount.BotCheckException);
    }

    @Test
    public void aVisitorOfAnAgeRestrictedTrackNeedsAnAccount() {
        PlayabilityVerdict verdict = PlayabilityVerdict.of(false, "LOGIN_REQUIRED", "This video may be inappropriate for some users.");
        assertEquals(PlayabilityVerdict.VISITOR_NEEDS_ACCOUNT, verdict);
        assertTrue(verdict.failure("LOGIN_REQUIRED", "") instanceof YouTubeAccount.SignInRequiredException);
    }

    @Test
    public void anyOtherRefusalOfAVisitorIsPlain() {
        PlayabilityVerdict verdict = PlayabilityVerdict.of(false, "UNPLAYABLE", "Video unavailable");
        assertEquals(PlayabilityVerdict.VISITOR_REFUSED, verdict);
        IOException failure = verdict.failure("UNPLAYABLE", "Video unavailable");
        assertFalse(failure instanceof YouTubeAccount.SignInRequiredException);
        assertFalse(failure instanceof YouTubeAccount.BotCheckException);
        assertEquals("YouTube refused the visitor request: UNPLAYABLE Video unavailable", failure.getMessage());
    }

    @Test
    public void anAccountThatMustConfirmItsAgeKeepsItsSession() {
        PlayabilityVerdict verdict = PlayabilityVerdict.of(true, "LOGIN_REQUIRED", "Sign in to confirm your age");
        assertEquals(PlayabilityVerdict.ACCOUNT_NEEDS_AGE_CONFIRMATION, verdict);
        assertFalse(verdict.dropsSession());
    }

    @Test
    public void aSignedInAccountAskedToSignInHasARevokedSession() {
        PlayabilityVerdict verdict = PlayabilityVerdict.of(true, "LOGIN_REQUIRED", "Sign in to continue");
        assertEquals(PlayabilityVerdict.SESSION_REVOKED, verdict);
        assertTrue(verdict.dropsSession());
        assertTrue(verdict.failure("LOGIN_REQUIRED", "") instanceof YouTubeAccount.SignInRequiredException);
    }

    @Test
    public void aBotCheckOfAnAccountDoesNotDropTheSession() {
        PlayabilityVerdict verdict = PlayabilityVerdict.of(true, "LOGIN_REQUIRED", SIGN_IN_BOT);
        assertEquals(PlayabilityVerdict.ACCOUNT_BOT, verdict);
        assertFalse(verdict.dropsSession());
        assertTrue(verdict.failure("LOGIN_REQUIRED", SIGN_IN_BOT) instanceof YouTubeAccount.BotCheckException);
    }

    @Test
    public void anyOtherRefusalOfAnAccountIsPlain() {
        assertEquals(PlayabilityVerdict.ACCOUNT_REFUSED, PlayabilityVerdict.of(true, "UNPLAYABLE", "Video unavailable"));
    }

    @Test
    public void theWordsAreWholeWords() {
        // "both" is not "bot", "page" and "message" are not "age".
        assertEquals(PlayabilityVerdict.SESSION_REVOKED, PlayabilityVerdict.of(true, "LOGIN_REQUIRED", "both on this page"));
        assertEquals(PlayabilityVerdict.VISITOR_NEEDS_ACCOUNT, PlayabilityVerdict.of(false, "LOGIN_REQUIRED", "a message"));
    }
}
