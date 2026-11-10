package app.revanced.extension.soundcloud.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ButtonPathTest {
    @Test
    public void autoGoesThroughTheAppOnlyOnXiaomiWhereNoNativeTapEverArrived() {
        assertTrue(ButtonPath.throughApp(ButtonPath.AUTO, true, false));
        assertFalse(ButtonPath.throughApp(ButtonPath.AUTO, true, true));
        assertFalse(ButtonPath.throughApp(ButtonPath.AUTO, false, false));
        assertFalse(ButtonPath.throughApp(ButtonPath.AUTO, false, true));
    }

    @Test
    public void aChosenWayIsKeptWhateverThePhone() {
        assertTrue(ButtonPath.throughApp(ButtonPath.THROUGH_APP, false, true));
        assertFalse(ButtonPath.throughApp(ButtonPath.SOUNDCLOUD, true, false));
    }

    @Test
    public void theLogNamesThePathAndTheReason() {
        assertEquals("activity (auto: Xiaomi firmware)", ButtonPath.describe(ButtonPath.AUTO, true, false));
        assertEquals("broadcast (auto: not Xiaomi)", ButtonPath.describe(ButtonPath.AUTO, false, false));
        assertEquals("broadcast (auto: Xiaomi, but a broadcast tap arrived before)", ButtonPath.describe(ButtonPath.AUTO, true, true));
        assertEquals("activity (chosen: through the app)", ButtonPath.describe(ButtonPath.THROUGH_APP, false, false));
    }
}
