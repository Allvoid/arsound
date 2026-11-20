package app.revanced.extension.soundcloud.navbar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LauncherStartTest {
    private static final String MAIN = "android.intent.action.MAIN";

    @Test
    public void aPlainStartIsALauncherStart() {
        assertTrue(BottomTabs.isLauncherStart(null, false, false, false));
        assertTrue(BottomTabs.isLauncherStart(MAIN, false, true, false));
    }

    @Test
    public void aLinkOrAnotherActionIsNot() {
        assertFalse(BottomTabs.isLauncherStart(null, true, false, false));
        assertFalse(BottomTabs.isLauncherStart("android.intent.action.VIEW", false, false, false));
    }

    @Test
    public void anIntentOnlyCarryingExtrasWithoutTheLauncherCategoryIsNot() {
        // A notification or a widget opens the app on a screen of its own: the start tab must not cover it 400 ms later.
        assertFalse(BottomTabs.isLauncherStart(null, false, false, true));
        assertFalse(BottomTabs.isLauncherStart(MAIN, false, false, true));
    }
}
