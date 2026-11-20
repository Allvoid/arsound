package app.revanced.extension.soundcloud.navbar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LauncherStartTest {
    private static final String MAIN = "android.intent.action.MAIN";

    @Test
    public void aPlainStartIsALauncherStart() {
        assertTrue(BottomTabs.isLauncherStart(null, false, false, false, false));
        assertTrue(BottomTabs.isLauncherStart(MAIN, false, true, false, false));
    }

    @Test
    public void aLinkOrAnotherActionIsNot() {
        assertFalse(BottomTabs.isLauncherStart(null, true, false, false, false));
        assertFalse(BottomTabs.isLauncherStart("android.intent.action.VIEW", false, false, false, false));
    }

    @Test
    public void anIntentOnlyCarryingExtrasWithoutTheLauncherCategoryIsNot() {
        // A notification or a widget opens the app on a screen of its own: the start tab must not cover it 400 ms later.
        assertFalse(BottomTabs.isLauncherStart(null, false, false, true, false));
        assertFalse(BottomTabs.isLauncherStart(MAIN, false, false, true, false));
    }

    @Test
    public void theLauncherScreenMarkTurnsAnExtrasOnlyIntentIntoALauncherStart() {
        // What the main screen really gets after a tap on the launcher: no action, no category, extras with the mark.
        assertTrue(BottomTabs.isLauncherStart(null, false, false, true, true));
        // The mark does not beat a link.
        assertFalse(BottomTabs.isLauncherStart(null, true, false, true, true));
        assertFalse(BottomTabs.isLauncherStart("android.intent.action.VIEW", false, false, true, true));
    }

    @Test
    public void theStartTabIsOpenedForEachLaunchNotOncePerProcess() {
        Object first = new Object();
        Object second = new Object();
        assertTrue(BottomTabs.isNewLaunch(null, first));
        // A turned phone makes a new activity with the same intent: left alone.
        assertFalse(BottomTabs.isNewLaunch(first, first));
        // Back, then a tap on the launcher: a new intent, a new launch.
        assertTrue(BottomTabs.isNewLaunch(first, second));
        assertFalse(BottomTabs.isNewLaunch(first, null));
    }
}
