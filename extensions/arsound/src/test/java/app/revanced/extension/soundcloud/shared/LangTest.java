package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LangTest {
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
    public void russianAppGetsRussianText() {
        Locale.setDefault(new Locale("ru", "RU"));
        assertEquals("Сохранить логи", Lang.text("Сохранить логи", "Save logs"));
    }

    @Test
    public void everyOtherLanguageGetsEnglishText() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals("Save logs", Lang.text("Сохранить логи", "Save logs"));
    }
}
