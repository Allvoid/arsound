package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SettingsSearchIndexTest {
    private static SettingsSearchIndex.Entry row(String section, String title, String description) {
        return new SettingsSearchIndex.Entry("screen", section, title, description, false);
    }

    @Test
    public void everyWordOfTheQueryMustBeFound() {
        SettingsSearchIndex index = new SettingsSearchIndex();
        index.add(row("Network", "Custom DNS", "Resolve through a chosen server"));
        index.add(row("Network", "Check IP again", "Country of the IP"));

        List<SettingsSearchIndex.Entry> found = index.search("dns server", 10);
        assertEquals(1, found.size());
        assertEquals("Custom DNS", found.get(0).title);
        assertTrue(index.search("dns banana", 10).isEmpty());
    }

    @Test
    public void titleMatchesLeadAndEqualScoresKeepTheScreenOrder() {
        SettingsSearchIndex index = new SettingsSearchIndex();
        index.add(row("A", "Alpha", "mentions theme here"));
        index.add(row("B", "Theme", ""));
        index.add(row("C", "Theme animation", ""));
        index.add(row("D", "Another", "theme too"));

        List<SettingsSearchIndex.Entry> found = index.search("theme", 10);
        assertEquals("Theme", found.get(0).title);
        assertEquals("Theme animation", found.get(1).title);
        assertEquals("Alpha", found.get(2).title);
        assertEquals("Another", found.get(3).title);
    }

    @Test
    public void theLimitCutsTheList() {
        SettingsSearchIndex index = new SettingsSearchIndex();
        for (int i = 0; i < 5; i++) index.add(row("S", "Row " + i, ""));
        assertEquals(3, index.search("row", 3).size());
    }

    @Test
    public void yoIsEAndPunctuationIsNotPartOfAWord() {
        SettingsSearchIndex index = new SettingsSearchIndex();
        index.add(row("Раздел", "Всё сразу", "Дубликаты, ремиксы"));
        assertEquals(1, index.search("все", 10).size());
        assertEquals(1, index.search("дубликаты", 10).size());
        assertEquals(1, index.search("«ремиксы»", 10).size());
    }

    @Test
    public void matchesGiveTheRangesToColour() {
        List<int[]> ranges = SettingsSearchIndex.matches("Custom DNS", "dns cus");
        assertEquals(2, ranges.size());
        assertEquals(7, ranges.get(0)[0]);
        assertEquals(10, ranges.get(0)[1]);
        assertEquals(0, ranges.get(1)[0]);
        assertEquals(3, ranges.get(1)[1]);
    }
}
