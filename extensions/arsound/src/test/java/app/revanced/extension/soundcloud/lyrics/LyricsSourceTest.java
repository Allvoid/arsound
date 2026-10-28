package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

public class LyricsSourceTest {
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
    public void lyricsFromLrclibAreTheDefault() {
        Lyrics lyrics = new Lyrics(new String[]{"a"}, null);
        assertEquals(Lyrics.Source.LRCLIB, lyrics.source);
    }

    @Test
    public void theCreditLineNamesWhoWroteTheWords() {
        Locale.setDefault(new Locale("ru"));
        assertEquals("Тексты: LRCLIB", Lyrics.Source.LRCLIB.caption());
        assertEquals("Распознано нейросетью · возможны ошибки", Lyrics.Source.NEURAL.caption());
        Locale.setDefault(Locale.ENGLISH);
        assertTrue(Lyrics.Source.NEURAL.caption().contains("neural network"));
    }

    @Test
    public void textWithoutTimingsKeepsItsSource() {
        Lyrics heard = new Lyrics(new String[]{"a"}, new long[]{0}, Lyrics.Source.NEURAL);
        assertEquals(Lyrics.Source.NEURAL, heard.withoutTimes().source);
    }
}
