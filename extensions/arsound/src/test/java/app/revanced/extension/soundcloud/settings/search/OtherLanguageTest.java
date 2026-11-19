package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import app.revanced.extension.soundcloud.shared.Lang;

public class OtherLanguageTest {
    private static SearchEntry row(String section, String title, String description) {
        return new SearchEntry("screen", section, title, description, false);
    }

    private static List<String> titlesFound(SearchIndex index, String query) {
        List<String> titles = new ArrayList<>();
        for (Hit hit : index.search(query, 8)) titles.add(hit.entry.title);
        return titles;
    }

    @Test
    public void aRowIsFoundByTheWordsOfTheOtherLanguageToo() {
        SearchEntry delay = row("Сеть", "Задержка запросов", "Ждать перед каждым запросом");
        SearchEntry other = row("Network", "Request delay", "Wait before every request");
        SearchIndex index = new SearchIndex();
        index.add(delay);
        index.add(row("Сеть", "Свой DNS", "Адреса через выбранный сервер"));

        assertFalse(titlesFound(index, "request delay").contains("Задержка запросов"));
        assertTrue(OtherLanguage.merge(Arrays.asList(delay), Arrays.asList(other)));
        assertTrue(titlesFound(index, "request delay").contains("Задержка запросов"));
        assertTrue(titlesFound(index, "задержка").contains("Задержка запросов"));
    }

    @Test
    public void theTextOfTheOtherLanguageComesInKeywordsOfEachRow() {
        SearchEntry main = row("Сеть", "Проверка сети", "Таблица по сайтам");
        SearchEntry other = row("Network", "Network check", "Table per site");
        other.addKeywords("diagnostics");
        OtherLanguage.merge(Arrays.asList(main), Arrays.asList(other));
        String words = main.keywords();
        assertTrue(words.contains("Network check"));
        assertTrue(words.contains("Table per site"));
        assertTrue(words.contains("diagnostics"));
        assertTrue(words.contains("Network"));
    }

    @Test
    public void aTextThatIsTheSameInBothLanguagesAddsNothing() {
        SearchEntry main = row("DNS", "DNS", "");
        SearchEntry other = row("DNS", "DNS", "");
        OtherLanguage.merge(Arrays.asList(main), Arrays.asList(other));
        assertEquals("", main.keywords());
    }

    @Test
    public void screensWithAnotherNumberOfRowsAreNotPaired() {
        SearchEntry main = row("Сеть", "Один", "");
        List<SearchEntry> main1 = Arrays.asList(main);
        List<SearchEntry> other2 = Arrays.asList(row("Network", "One", ""), row("Network", "Two", ""));
        assertFalse(OtherLanguage.merge(main1, other2));
        assertEquals("", main.keywords());
    }

    @Test
    public void theLanguageIsForcedOnlyInsideTheTask() {
        boolean before = Lang.russian();
        String[] seen = new String[2];
        Lang.withLanguage(true, () -> seen[0] = Lang.text("да", "yes"));
        Lang.withLanguage(false, () -> seen[1] = Lang.text("да", "yes"));
        assertEquals("да", seen[0]);
        assertEquals("yes", seen[1]);
        assertEquals(before, Lang.russian());
    }
}
