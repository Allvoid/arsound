package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioDiagnosticsTest {
    @Test
    public void fullAndQuietVolumesAreNotSilent() {
        assertFalse(AudioDiagnostics.isSilent(1f));
        assertFalse(AudioDiagnostics.isSilent(0.5f));
        assertFalse(AudioDiagnostics.isSilent(AudioDiagnostics.SILENT_BELOW));
    }

    @Test
    public void practicallyZeroVolumeIsSilent() {
        assertTrue(AudioDiagnostics.isSilent(0f));
        assertTrue(AudioDiagnostics.isSilent(0.01f));
    }

    @Test
    public void justBelowTheLimitIsSilent() {
        assertTrue(AudioDiagnostics.isSilent(AudioDiagnostics.SILENT_BELOW - 0.001f));
    }
}
