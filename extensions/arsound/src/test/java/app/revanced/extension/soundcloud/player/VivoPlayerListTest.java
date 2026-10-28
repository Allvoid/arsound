package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

public class VivoPlayerListTest {
    private Locale saved;

    @Before
    public void saveLocale() {
        saved = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(saved);
    }

    @Test
    public void cooperateAppsAreTheOnesWithTheirOwnIntegration() {
        assertTrue(VivoPlayerList.isCooperate("com.spotify.music"));
        assertTrue(VivoPlayerList.isCooperate("com.android.bbkmusic"));
        assertFalse(VivoPlayerList.isCooperate("com.tencent.mm"));
        assertFalse(VivoPlayerList.isCooperate("com.spotify"));
    }

    @Test
    public void someAppsDoNotShowOnTheLockScreen() {
        assertFalse(VivoPlayerList.showsOnLockScreen("com.larus.nova"));
        assertTrue(VivoPlayerList.showsOnLockScreen("com.spotify.music"));
    }

    @Test
    public void everyStateHasAnExplanationInBothLanguages() {
        for (VivoMusicCard.State state : VivoMusicCard.State.values()) {
            Locale.setDefault(new Locale("ru"));
            String russian = state.describe();
            Locale.setDefault(Locale.ENGLISH);
            String english = state.describe();
            assertFalse(russian.isEmpty());
            assertFalse(english.isEmpty());
            assertFalse(russian.equals(english));
        }
    }

    @Test
    public void unlistedStatesPointToTheHowTo() {
        Locale.setDefault(Locale.ENGLISH);
        assertEquals(VivoMusicCard.State.NOT_LISTED.describe(), VivoMusicCard.State.NOT_VIVO.describe());
        assertTrue(VivoMusicCard.State.NOT_LISTED.describe().contains("Tap to see how to add it"));
    }
}
