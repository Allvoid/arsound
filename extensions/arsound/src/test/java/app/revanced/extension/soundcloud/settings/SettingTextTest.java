package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SettingTextTest {
    @Test
    public void aShortDescriptionIsAllSummary() {
        SettingText text = SettingText.split("Версия 1.2.3");
        assertEquals("Версия 1.2.3", text.summary);
        assertNull(text.details);
    }

    @Test
    public void nothingGivesAnEmptySummary() {
        assertEquals("", SettingText.split(null).summary);
        assertEquals("", SettingText.split("   ").summary);
        assertNull(SettingText.split("").details);
    }

    @Test
    public void theFirstShortSentenceIsTheSummaryAndTheWholeTextIsTheDetails() {
        String full = "Не показывает экран покупки Go. Купить подписку в моде всё равно нельзя, "
                + "а все предложения и баннеры скрыты по умолчанию.";
        SettingText text = SettingText.split(full);
        assertEquals("Не показывает экран покупки Go.", text.summary);
        assertEquals(full, text.details);
    }

    @Test
    public void aLongFirstSentenceIsCutAtAWordWithAnEllipsis() {
        String full = "Приложение реже выходит в сеть в фоне: новые сообщения проверяются раз в пять минут вместо "
                + "каждых тридцати секунд, и встроенные сервисы аналитики не отправляют отчёты.";
        SettingText text = SettingText.split(full);
        assertTrue(text.summary, text.summary.endsWith("…"));
        assertTrue(text.summary, text.summary.length() <= SettingText.SUMMARY_LIMIT + 1);
        assertTrue(text.summary, full.startsWith(text.summary.substring(0, text.summary.length() - 1)));
        assertEquals(full, text.details);
    }

    @Test
    public void aDotInsideAWordIsNotTheEndOfASentence() {
        String full = "После нажатия название и исполнитель играющего трека отправляются на lrclib.net, найденный "
                + "текст сохраняется на телефоне и потом открывается сразу.";
        SettingText text = SettingText.split(full);
        assertTrue(text.summary, text.summary.endsWith("…"));
        assertEquals(full, text.details);
    }

    @Test
    public void textWithALineBreakIsLeftAlone() {
        String value = "500 МБ\nСюда SoundCloud сохраняет части треков, которые вы слушали из сети, чтобы при повторе не качать их снова.";
        SettingText text = SettingText.split(value);
        assertEquals(value, text.summary);
        assertNull(text.details);
    }
}
