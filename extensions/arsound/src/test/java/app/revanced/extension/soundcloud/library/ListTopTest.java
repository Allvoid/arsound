package app.revanced.extension.soundcloud.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ListTopTest {
    @Test
    public void topStaysTop() {
        assertEquals(0, ListTop.collapse(0));
    }

    @Test
    public void everyRowBelowTheTopGivesTheSameValue() {
        // The derived state compares values: equal ones for rows 1, 2, 57 keep the screen from being composed again.
        assertEquals(ListTop.collapse(1), ListTop.collapse(2));
        assertEquals(ListTop.collapse(2), ListTop.collapse(57));
        assertEquals(1, ListTop.collapse(1500));
    }

    @Test
    public void theScreenStillTellsTopFromBelow() {
        // The screen reads the value as a number and checks it against zero.
        assertEquals(0, ((Number) ListTop.collapse(0)).intValue());
        assertEquals(1, ((Number) ListTop.collapse(3)).intValue());
    }

    @Test
    public void otherNumberTypesWork() {
        assertEquals(0, ListTop.collapse(0L));
        assertEquals(1, ListTop.collapse(7L));
    }

    @Test
    public void anythingElseIsLeftAsItIs() {
        Object other = "not a number";
        assertSame(other, ListTop.collapse(other));
        assertNull(ListTop.collapse(null));
    }
}
