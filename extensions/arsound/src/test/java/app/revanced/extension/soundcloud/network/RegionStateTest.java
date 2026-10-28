package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RegionStateTest {
    @Test
    public void anAnswerOfTheCurrentNetworkIsKept() {
        RegionState state = new RegionState();
        assertTrue(state.publishCheck(state.generation(), "DE"));
        assertEquals("DE", state.country());
    }

    @Test
    public void anAnswerOfAnOlderNetworkIsDropped() {
        RegionState state = new RegionState();
        int started = state.generation();
        state.networkChanged(false);
        assertFalse(state.publishCheck(started, "RU"));
        assertNull(state.country());
    }

    @Test
    public void aNetworkChangeForgetsTheCountry() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), "DE");
        state.networkChanged(false);
        assertNull(state.country());
    }

    @Test
    public void aFailedCheckIsRemembered() {
        RegionState state = new RegionState();
        assertTrue(state.publishCheck(state.generation(), null));
        assertTrue(state.lastFailedCheck() > 0);
        assertNull(state.country());
        state.forgetFailure();
        assertEquals(0, state.lastFailedCheck());
    }

    @Test
    public void aChangeCanKeepOrResetTheFailedCheck() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), null);
        state.networkChanged(false);
        assertTrue(state.lastFailedCheck() > 0);
        state.networkChanged(true);
        assertEquals(0, state.lastFailedCheck());
    }

    @Test
    public void theNoticeIsShownOncePerCountry() {
        RegionState state = new RegionState();
        assertTrue(state.claimNotice());
        assertFalse(state.claimNotice());
        state.forgetFailure();
        assertTrue(state.claimNotice());
    }

    @Test
    public void aNewCountryInTheBackgroundShowsTheNoticeAgain() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), "RU");
        state.claimNotice();
        state.publishBackgroundCheck(state.generation(), "RU", "RU", code -> true);
        assertFalse(state.claimNotice());
        state.publishBackgroundCheck(state.generation(), "RU", "DE", code -> false);
        assertEquals("DE", state.country());
        assertTrue(state.claimNotice());
    }

    @Test
    public void aFailedBackgroundCheckOnAnAllowedNetworkForgetsTheCountry() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), "DE");
        state.publishBackgroundCheck(state.generation(), "DE", null, code -> false);
        assertNull(state.country());
        assertTrue(state.lastFailedCheck() > 0);
    }

    @Test
    public void aFailedBackgroundCheckOnABlockedNetworkKeepsTheCountry() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), "RU");
        state.publishBackgroundCheck(state.generation(), "RU", null, code -> true);
        assertEquals("RU", state.country());
    }

    @Test
    public void aBackgroundAnswerOfAnOlderNetworkIsDropped() {
        RegionState state = new RegionState();
        state.publishCheck(state.generation(), "RU");
        int started = state.generation();
        state.networkChanged(false);
        state.publishBackgroundCheck(started, "RU", "DE", code -> false);
        assertNull(state.country());
    }
}
