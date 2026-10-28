package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyboardLayoutTest {
    @Test
    public void englishTypingBecomesRussian() {
        assertEquals("память", KeyboardLayout.toCyrillic("gfvznm"));
        assertEquals("обои", KeyboardLayout.toCyrillic("j,jb"));
    }

    @Test
    public void russianTypingBecomesEnglish() {
        assertEquals("wallpaper", KeyboardLayout.toLatin("цфддзфзук"));
        assertEquals("dns", KeyboardLayout.toLatin("вты"));
    }

    @Test
    public void mixedInputAndOtherCharactersPassThrough() {
        assertEquals("п1амять", KeyboardLayout.toCyrillic("g1fvznm"));
        assertEquals("18+", KeyboardLayout.toCyrillic("18+"));
        assertEquals("ё", KeyboardLayout.toCyrillic("ё"));
    }

    @Test
    public void punctuationKeysAreLettersOnTheRussianLayout() {
        assertEquals("хранилище", KeyboardLayout.toCyrillic("[hfybkbot"));
        assertEquals("батарея", KeyboardLayout.toCyrillic(",fnfhtz"));
        assertEquals("кэш", KeyboardLayout.toCyrillic("r'i"));
        assertTrue(KeyboardLayout.isLatinTyped("[hfybkbot"));
        assertTrue(KeyboardLayout.isLatinTyped("dns"));
        assertFalse(KeyboardLayout.isLatinTyped("[;'"));
        assertFalse(KeyboardLayout.isLatinTyped("dns1"));
        assertFalse(KeyboardLayout.isLatinTyped(""));
    }

    @Test
    public void onlyWordsWithAPunctuationKeyInsideAreConvertedByTheQueryPass() {
        assertEquals("хранилище", KeyboardLayout.punctuationKeysAsLetters("[hfybkbot"));
        assertEquals("обои", KeyboardLayout.punctuationKeysAsLetters("J,jb"));
        assertEquals("dns кэш", KeyboardLayout.punctuationKeysAsLetters("dns r'i"));
        // No such key, or only at the end where it may be a full stop or a comma: left alone.
        assertNull(KeyboardLayout.punctuationKeysAsLetters("gfvznm"));
        assertNull(KeyboardLayout.punctuationKeysAsLetters("dns,"));
        assertNull(KeyboardLayout.punctuationKeysAsLetters("  "));
        assertNull(KeyboardLayout.punctuationKeysAsLetters("настройки"));
    }

    @Test
    public void scriptsAreTold() {
        assertTrue(KeyboardLayout.isLatin("dns"));
        assertFalse(KeyboardLayout.isLatin("dns1"));
        assertTrue(KeyboardLayout.isCyrillic("сеть"));
        assertFalse(KeyboardLayout.isCyrillic(""));
    }
}
