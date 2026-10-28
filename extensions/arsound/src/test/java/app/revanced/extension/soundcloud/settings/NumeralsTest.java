package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NumeralsTest {
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
    public void russianTracksFollowTheLastDigits() {
        assertEquals("1 трек", Numerals.russianTracks(1));
        assertEquals("2 трека", Numerals.russianTracks(2));
        assertEquals("5 треков", Numerals.russianTracks(5));
        assertEquals("11 треков", Numerals.russianTracks(11));
        assertEquals("14 треков", Numerals.russianTracks(14));
        assertEquals("21 трек", Numerals.russianTracks(21));
        assertEquals("22 трека", Numerals.russianTracks(22));
        assertEquals("111 треков", Numerals.russianTracks(111));
        assertEquals("0 треков", Numerals.russianTracks(0));
    }

    @Test
    public void russianDays() {
        Locale.setDefault(new Locale("ru", "RU"));
        assertEquals("1 день", Numerals.days(1));
        assertEquals("3 дня", Numerals.days(3));
        assertEquals("30 дней", Numerals.days(30));
        assertEquals("12 дней", Numerals.days(12));
        assertEquals("Год", Numerals.days(365));
    }

    @Test
    public void englishDays() {
        Locale.setDefault(Locale.US);
        assertEquals("1 day", Numerals.days(1));
        assertEquals("7 days", Numerals.days(7));
        assertEquals("A year", Numerals.days(365));
    }
}
