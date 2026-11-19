package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DnsModesTest {
    @Test
    public void systemOnlySkipsTheCustomServersEvenWhenTheOptionIsOn() {
        assertFalse(DnsModes.asksCustomServers(true, DnsModes.SYSTEM));
    }

    @Test
    public void otherModesAskTheServersOnlyWhileTheOptionIsOn() {
        assertTrue(DnsModes.asksCustomServers(true, DnsModes.AUTO));
        assertTrue(DnsModes.asksCustomServers(true, DnsModes.DOH));
        assertTrue(DnsModes.asksCustomServers(true, DnsModes.PLAIN));
        assertFalse(DnsModes.asksCustomServers(false, DnsModes.AUTO));
        assertFalse(DnsModes.asksCustomServers(false, DnsModes.SYSTEM));
    }

    @Test
    public void onlyTheFourModesAreKnown() {
        assertTrue(DnsModes.isKnown("system"));
        assertTrue(DnsModes.isKnown("auto"));
        assertFalse(DnsModes.isKnown("dot"));
        assertFalse(DnsModes.isKnown(null));
    }
}
