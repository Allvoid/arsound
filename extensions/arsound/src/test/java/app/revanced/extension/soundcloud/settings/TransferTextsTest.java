package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TransferTextsTest {
    private Locale oldLocale;

    @Before
    public void rememberLocale() {
        oldLocale = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(oldLocale);
    }

    private static TransferBackup backup(String version, String date, int values, int ignored, int wrongType) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (int i = 0; i < values; i++) settings.put("key_" + i, true);
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        files.put("revanced_soundcloud", settings);
        return new TransferBackup(1, version, date, files, ignored, wrongType);
    }

    @Test
    public void russianCountsTakeTheRightForm() {
        Locale.setDefault(new Locale("ru"));
        assertEquals("1 значение", TransferTexts.values(1));
        assertEquals("2 значения", TransferTexts.values(2));
        assertEquals("5 значений", TransferTexts.values(5));
        assertEquals("11 значений", TransferTexts.values(11));
        assertEquals("21 значение", TransferTexts.values(21));
        assertEquals("112 значений", TransferTexts.values(112));
        assertEquals("0 значений", TransferTexts.values(0));
    }

    @Test
    public void englishCountsTakeTheRightForm() {
        Locale.setDefault(Locale.ENGLISH);
        assertEquals("1 value", TransferTexts.values(1));
        assertEquals("2 values", TransferTexts.values(2));
        assertEquals("0 values", TransferTexts.values(0));
    }

    @Test
    public void theQuestionSaysHowMuchFromWhereAndWhatItReplaces() {
        Locale.setDefault(new Locale("ru"));
        String text = TransferTexts.confirmation(backup("1.0.1", "2026-11-03", 214, 0, 0));
        assertTrue(text, text.contains("214 значений"));
        assertTrue(text, text.contains("Arsound 1.0.1, 2026-11-03"));
        assertTrue(text, text.contains("заменят"));
        assertTrue(text, text.contains("перезапустится"));
        assertFalse(text, text.contains("Пропущено"));

        Locale.setDefault(Locale.ENGLISH);
        text = TransferTexts.confirmation(backup("1.0.1", "2026-11-03", 214, 0, 0));
        assertTrue(text, text.contains("214 values"));
        assertTrue(text, text.contains("Arsound 1.0.1, 2026-11-03"));
        assertTrue(text, text.contains("replace"));
        assertTrue(text, text.contains("restarts"));
        assertFalse(text, text.contains("Left out"));
    }

    @Test
    public void aFileWithoutAVersionOrADayDoesNotMakeEmptyBrackets() {
        Locale.setDefault(Locale.ENGLISH);
        String text = TransferTexts.confirmation(backup("", "", 3, 0, 0));
        assertFalse(text, text.contains("()"));
        assertTrue(text, text.startsWith("From the file this takes 3 values."));

        text = TransferTexts.confirmation(backup("1.0.1", "", 3, 0, 0));
        assertTrue(text, text.startsWith("From the file (Arsound 1.0.1) this takes"));
        text = TransferTexts.confirmation(backup("", "2026-11-03", 3, 0, 0));
        assertTrue(text, text.startsWith("From the file (2026-11-03) this takes"));
    }

    @Test
    public void theQuestionCountsWhatIsLeftOut() {
        Locale.setDefault(new Locale("ru"));
        String text = TransferTexts.confirmation(backup("1.0.1", "2026-11-03", 10, 2, 1));
        assertTrue(text, text.contains("Пропущено: 3 значения"));

        Locale.setDefault(Locale.ENGLISH);
        text = TransferTexts.confirmation(backup("1.0.1", "2026-11-03", 10, 2, 1));
        assertTrue(text, text.contains("Left out: 3 values"));
    }

    @Test
    public void everyReasonHasItsOwnWordsInBothLanguages() {
        for (Locale locale : new Locale[]{new Locale("ru"), Locale.ENGLISH}) {
            Locale.setDefault(locale);
            Set<String> seen = new HashSet<>();
            for (TransferException.Reason reason : TransferException.Reason.values()) {
                String text = TransferTexts.failure(reason);
                assertFalse(reason.toString(), text.isEmpty());
                assertTrue(reason + " repeats a text", seen.add(text));
            }
        }
    }

    @Test
    public void aForeignFileIsToldWhereTheRightOneComesFrom() {
        Locale.setDefault(Locale.ENGLISH);
        assertTrue(TransferTexts.failure(TransferException.Reason.NOT_SETTINGS).contains("Export settings"));
        assertTrue(TransferTexts.failure(TransferException.Reason.NEWER_FORMAT).contains("Update"));
        Locale.setDefault(new Locale("ru"));
        assertTrue(TransferTexts.failure(TransferException.Reason.NOT_SETTINGS).contains("Экспорт настроек"));
        assertTrue(TransferTexts.failure(TransferException.Reason.NEWER_FORMAT).contains("Обновите"));
    }

    @Test
    public void theResultsNameTheCounts() {
        Locale.setDefault(Locale.ENGLISH);
        assertEquals("Settings saved to the file: 5 values.", TransferTexts.exported(new TransferExport("{}", 5, 0)));

        TransferChanges changes = new TransferChanges();
        changes.writesOf("revanced_soundcloud").put("a", true);
        changes.writesOf("revanced_soundcloud").put("b", true);
        assertEquals("Settings applied: 2 values. Restarting the app…", TransferTexts.imported(changes));
        changes.typeConflicts = 1;
        assertTrue(TransferTexts.imported(changes).endsWith("Left out: 1 value, not fitting this version."));

        Locale.setDefault(new Locale("ru"));
        assertEquals("Настройки сохранены в файл: 5 значений.", TransferTexts.exported(new TransferExport("{}", 5, 0)));
        assertTrue(TransferTexts.imported(changes).startsWith("Настройки применены: 2 значения."));
    }
}
