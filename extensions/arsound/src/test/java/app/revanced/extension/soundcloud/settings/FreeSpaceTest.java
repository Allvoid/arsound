package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FreeSpaceTest {
    @Test
    public void theLineNamesTheInternalStorageItMeasures() {
        // It reads the internal volume: a card the downloads go to is not it, so the label must not say "the phone".
        assertTrue(FreeSpace.label(false).toLowerCase().contains("internal"));
        assertTrue(FreeSpace.label(true).toLowerCase().contains("внутренн"));
    }
}
