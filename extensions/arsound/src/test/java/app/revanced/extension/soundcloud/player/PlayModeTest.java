package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

public class PlayModeTest {
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
    public void modesGoRoundInTheOrderOfTheButton() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.ORDER.next());
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL.next());
        assertEquals(PlayMode.SHUFFLE, PlayMode.REPEAT_ONE.next());
        assertEquals(PlayMode.ORDER, PlayMode.SHUFFLE.next());
    }

    @Test
    public void shuffledQueueIsShuffleWhateverItRepeats() {
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(true, null));
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(true, "REPEAT_ALL"));
    }

    @Test
    public void repeatSettingGivesTheMode() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.of(false, "REPEAT_ALL"));
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.of(false, "REPEAT_ONE"));
        assertEquals(PlayMode.ORDER, PlayMode.of(false, "REPEAT_NONE"));
        assertEquals(PlayMode.ORDER, PlayMode.of(false, "something new"));
    }

    @Test
    public void titleFollowsTheLanguage() {
        Locale.setDefault(new Locale("ru"));
        assertEquals("Вперемешку", PlayMode.SHUFFLE.title());
        Locale.setDefault(Locale.ENGLISH);
        assertEquals("Repeat track", PlayMode.REPEAT_ONE.title());
    }

    @Test
    public void iconNamesAreTheOnesTheResourcesUse() {
        assertEquals("arsound_play_mode_order", PlayMode.ORDER.icon);
        assertEquals("arsound_play_mode_repeat_all", PlayMode.REPEAT_ALL.icon);
        assertEquals("arsound_play_mode_repeat_one", PlayMode.REPEAT_ONE.icon);
        assertEquals("arsound_play_mode_shuffle", PlayMode.SHUFFLE.icon);
    }
}
