package app.revanced.extension.soundcloud.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CardLookTest {
    private static final int COLOUR = 0xFF203040;

    @Test
    public void theFillGoesFromClearToSolid() {
        assertEquals(0x00203040, CardLook.fill(COLOUR, 0));
        assertEquals(0xFF203040, CardLook.fill(COLOUR, 100));
        assertEquals(0x80203040, CardLook.fill(COLOUR, 50));
    }

    @Test
    public void aPercentOutOfRangeIsTakenForTheNearestEnd() {
        assertEquals(0x00203040, CardLook.fill(COLOUR, -5));
        assertEquals(0xFF203040, CardLook.fill(COLOUR, 250));
    }

    @Test
    public void theOutlineIsTheAccentAPlainLineOrNone() {
        int accent = 0xFFE0222E, text = 0xFFFFFFFF;
        assertEquals(0x66E0222E, CardLook.outline(CardLook.OUTLINE_ACCENT, accent, text));
        assertEquals(0x38FFFFFF, CardLook.outline(CardLook.OUTLINE_PLAIN, accent, text));
        assertEquals(0, CardLook.outline(CardLook.OUTLINE_NONE, accent, text));
    }

    @Test
    public void anUnknownOutlineIsTheAccent() {
        assertEquals(0x66E0222E, CardLook.outline(7, 0xFFE0222E, 0xFFFFFFFF));
    }
}
