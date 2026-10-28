package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

public class LanguagePolicyTest {
    @Test
    public void theDefaultSettingIsRussianInARussianApp() {
        assertEquals("ru", LanguagePolicy.defaultSetting(true));
        assertEquals("auto", LanguagePolicy.defaultSetting(false));
    }

    @Test
    public void onlyRussianEnglishAndThePhonesLanguageAreExpected() {
        assertTrue(LanguagePolicy.isExpected("ru", "en"));
        assertTrue(LanguagePolicy.isExpected("en", "ru"));
        assertTrue(LanguagePolicy.isExpected("de", "de"));
        assertFalse(LanguagePolicy.isExpected("ja", "ru"));
        assertFalse(LanguagePolicy.isExpected("pt", "en"));
        assertFalse(LanguagePolicy.isExpected("", "en"));
        assertFalse(LanguagePolicy.isExpected(null, "en"));
    }

    @Test
    public void anUnexpectedLanguageBecomesTheUsualOne() {
        assertEquals("ru", LanguagePolicy.pick("ja", "ru", true));
        assertEquals("en", LanguagePolicy.pick("ja", "en", false));
        assertEquals("ru", LanguagePolicy.pick("pt", "ru", true));
    }

    @Test
    public void anExpectedLanguageStaysAndNothingStaysNothing() {
        assertEquals("ru", LanguagePolicy.pick("ru", "en", false));
        assertEquals("en", LanguagePolicy.pick("en", "ru", true));
        assertEquals("de", LanguagePolicy.pick("de", "de", false));
        assertEquals("", LanguagePolicy.pick("", "ru", true));
        assertEquals("", LanguagePolicy.pick(null, "en", false));
    }

    @Test
    public void scriptsFollowTheLanguage() {
        assertEquals(EnumSet.of(LanguagePolicy.Script.CYRILLIC), LanguagePolicy.scriptsFor("ru", "en"));
        assertEquals(EnumSet.of(LanguagePolicy.Script.LATIN), LanguagePolicy.scriptsFor("en", "ru"));
        assertEquals(EnumSet.of(LanguagePolicy.Script.OTHER), LanguagePolicy.scriptsFor("ja", "ru"));
        assertEquals(EnumSet.of(LanguagePolicy.Script.CYRILLIC, LanguagePolicy.Script.LATIN),
                LanguagePolicy.scriptsFor("", "ru"));
        assertTrue(LanguagePolicy.scriptsFor("", "ja").contains(LanguagePolicy.Script.OTHER));
    }

    @Test
    public void lettersAreToldApartByScript() {
        assertEquals(LanguagePolicy.Script.CYRILLIC, LanguagePolicy.scriptOf('я'));
        assertEquals(LanguagePolicy.Script.CYRILLIC, LanguagePolicy.scriptOf('ё'));
        assertEquals(LanguagePolicy.Script.LATIN, LanguagePolicy.scriptOf('k'));
        assertEquals(LanguagePolicy.Script.LATIN, LanguagePolicy.scriptOf('é'));
        assertEquals(LanguagePolicy.Script.OTHER, LanguagePolicy.scriptOf('今'));
        assertEquals(LanguagePolicy.Script.OTHER, LanguagePolicy.scriptOf('も'));
    }

    @Test
    public void theShareCountsLettersOnly() {
        assertEquals(6 / 11.0, LanguagePolicy.shareIn("Привет hello", EnumSet.of(LanguagePolicy.Script.CYRILLIC)), 1e-9);
        assertEquals(1.0, LanguagePolicy.shareIn("17 19 !", EnumSet.of(LanguagePolicy.Script.CYRILLIC)), 1e-9);
        assertEquals(0, LanguagePolicy.letters("17 19 !"));
        assertEquals(3, LanguagePolicy.letters("a1b2c"));
    }
}
