package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

public class LyricsStatusTest {
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
    public void onlyRealAnswersAreStored() {
        assertTrue(LyricsResult.Status.FOUND.storable);
        assertTrue(LyricsResult.Status.INSTRUMENTAL.storable);
        assertTrue(LyricsResult.Status.MISSING.storable);
        assertFalse(LyricsResult.Status.OFFLINE.storable);
        assertFalse(LyricsResult.Status.ERROR.storable);
        assertFalse(LyricsResult.offline().isStorable());
        assertTrue(LyricsResult.missing().isStorable());
    }

    @Test
    public void onlyFailuresCanBeRetried() {
        for (LyricsResult.Status status : LyricsResult.Status.values()) {
            assertEquals(!status.storable, status.retryable);
        }
    }

    @Test
    public void storedWordsAreTheOnesFilesAlreadyUse() {
        // Files written by earlier versions carry these words.
        assertEquals("found", LyricsResult.Status.FOUND.cacheKey);
        assertEquals("instrumental", LyricsResult.Status.INSTRUMENTAL.cacheKey);
        assertEquals("missing", LyricsResult.Status.MISSING.cacheKey);
        assertEquals(LyricsResult.Status.MISSING, LyricsResult.Status.fromCacheKey("missing"));
        assertNull(LyricsResult.Status.fromCacheKey("offline"));
        assertNull(LyricsResult.Status.fromCacheKey(""));
    }

    @Test
    public void messagesFollowTheLanguage() {
        Locale.setDefault(new Locale("ru"));
        assertEquals("Текст не найден", LyricsResult.Status.MISSING.message());
        assertEquals("Нет подключения.\nНажмите, чтобы повторить", LyricsResult.Status.OFFLINE.message());
        Locale.setDefault(Locale.ENGLISH);
        assertEquals("No lyrics found", LyricsResult.Status.MISSING.message());
        assertEquals("Could not load the lyrics.\nTap to try again", LyricsResult.Status.ERROR.message());
        assertNull(LyricsResult.Status.FOUND.message());
    }
}
