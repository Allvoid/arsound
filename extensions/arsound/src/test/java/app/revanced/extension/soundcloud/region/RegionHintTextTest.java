package app.revanced.extension.soundcloud.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RegionHintTextTest {
    private static String all(boolean russian) {
        StringBuilder text = new StringBuilder();
        for (RegionHintText.Paragraph paragraph : RegionHintText.paragraphs(russian)) {
            text.append(paragraph.lead).append(' ').append(paragraph.body).append('\n');
        }
        return text.toString();
    }

    @Test
    public void russianTextNamesEveryButtonTheUserHasToPress() {
        String text = all(true);
        assertTrue(text.contains("VPN"));
        assertTrue(text.contains("«Обновления и данные»"));
        assertTrue(text.contains("«Сбросить данные SoundCloud»"));
        assertTrue(text.contains("«Сеть»"));
        assertTrue(text.contains("«Не выходить в сеть с IP выбранных стран»"));
    }

    @Test
    public void englishTextNamesEveryButtonTheUserHasToPress() {
        String text = all(false);
        assertTrue(text.contains("VPN"));
        assertTrue(text.contains("\"Updates and data\""));
        assertTrue(text.contains("\"Reset SoundCloud data\""));
        assertTrue(text.contains("\"Network\""));
        assertTrue(text.contains("\"Stay offline on IPs of chosen countries\""));
    }

    @Test
    public void bothLanguagesHaveTheSameSteps() {
        assertEquals(RegionHintText.paragraphs(true).length, RegionHintText.paragraphs(false).length);
        for (boolean russian : new boolean[]{true, false}) {
            RegionHintText.Paragraph[] paragraphs = RegionHintText.paragraphs(russian);
            assertEquals("", paragraphs[0].lead);
            for (int i = 1; i < paragraphs.length; i++) {
                assertTrue(paragraphs[i].lead.startsWith(i + ". "));
            }
        }
    }

    @Test
    public void textsAreNotEmptyAndDiffer() {
        assertFalse(RegionHintText.title(true).isEmpty());
        assertFalse(RegionHintText.title(true).equals(RegionHintText.title(false)));
        assertFalse(RegionHintText.dontShowAgain(true).equals(RegionHintText.dontShowAgain(false)));
        assertFalse(RegionHintText.bringBack(true).equals(RegionHintText.bringBack(false)));
    }
}
