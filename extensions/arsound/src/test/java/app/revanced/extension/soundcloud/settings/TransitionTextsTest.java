package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TransitionTextsTest {
    @Test
    public void theTransitionTextsDoNotPromiseAnOverlapOfAStreamedTrack() throws Exception {
        // The overlap plays only a track on the phone; the texts of the rows must say so, as the README does.
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/app/revanced/extension/soundcloud/settings/PlaybackScreen.java")),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("открывается из сети во втором плеере"));
        assertFalse(source.contains("else streamed from the network"));
        assertFalse(source.contains("opened from the network for this"));
        assertFalse(source.contains("для этого открывается из сети"));
        // And they say what is true now.
        assertTrue(source.contains("трек из сети затихает и нарастает по очереди"));
        assertTrue(source.contains("a streamed one fades one after the other"));
        assertTrue(source.contains("Только для трека на телефоне"));
        assertTrue(source.contains("Only for a track on the phone"));
    }
}
