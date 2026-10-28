package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsIconsTest {
    @Test
    public void theOwnPictureComesFirstThenSoundCloudsOwn() {
        assertArrayEquals(new String[]{"arsound_set_wallpaper", "ic_actions_image"}, SettingsIcons.candidates("wallpaper"));
        assertArrayEquals(new String[]{"arsound_set_region", "ic_labels_location", "ic_actions_block"}, SettingsIcons.candidates("region"));
    }

    @Test
    public void aKeyWithoutAFallbackHasOnlyItsOwnPicture() {
        assertArrayEquals(new String[]{"arsound_set_nothing_like_this"}, SettingsIcons.candidates("nothing_like_this"));
    }

    @Test
    public void lyricsUseThePlayersButtonIcon() {
        assertEquals("arsound_lyrics", SettingsIcons.candidates("lyrics")[1]);
    }
}
