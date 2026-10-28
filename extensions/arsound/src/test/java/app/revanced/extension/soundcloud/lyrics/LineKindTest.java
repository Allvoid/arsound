package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LineKindTest {
    @Test
    public void kindFollowsTextAndTimings() {
        assertEquals(LineKind.TIMED, LineKind.of("words", true));
        assertEquals(LineKind.PLAIN, LineKind.of("words", false));
        assertEquals(LineKind.PAUSE, LineKind.of("", true));
        assertEquals(LineKind.GAP, LineKind.of("", false));
    }

    @Test
    public void pauseShowsDotsAndOthersShowTheirText() {
        assertEquals("• • •", LineKind.PAUSE.text(""));
        assertEquals("words", LineKind.TIMED.text("words"));
        assertEquals("", LineKind.GAP.text(""));
    }

    @Test
    public void timedLinesFadeWithDistance() {
        assertEquals(1f, LineKind.TIMED.alpha(0), 0f);
        assertEquals(0.62f, LineKind.TIMED.alpha(1), 0f);
        assertEquals(0.46f, LineKind.TIMED.alpha(2), 0f);
        assertEquals(0.34f, LineKind.TIMED.alpha(7), 0f);
        assertEquals(1f, LineKind.TIMED.scale(0), 0f);
        assertEquals(0.88f, LineKind.TIMED.scale(1), 0f);
    }

    @Test
    public void pauseIsFainterThanWordsExceptWhenSinging() {
        assertEquals(1f, LineKind.PAUSE.alpha(0), 0f);
        assertEquals(0.62f * 0.7f, LineKind.PAUSE.alpha(1), 0f);
    }

    @Test
    public void plainTextIsNeverDimmedOrShrunk() {
        assertEquals(0.92f, LineKind.PLAIN.alpha(5), 0f);
        assertEquals(1f, LineKind.PLAIN.scale(5), 0f);
        assertEquals(0.92f, LineKind.GAP.alpha(0), 0f);
    }

    @Test
    public void sizesAndSpacing() {
        assertEquals(0.6f, LineKind.PAUSE.textScale, 0f);
        assertEquals(6, LineKind.PAUSE.verticalPaddingDp);
        assertEquals(9, LineKind.TIMED.verticalPaddingDp);
    }
}
