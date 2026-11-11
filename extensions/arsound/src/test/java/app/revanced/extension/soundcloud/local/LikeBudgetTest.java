package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LikeBudgetTest {
    private static final long HOUR = 60L * 60 * 1000;

    @Test
    public void countsOnlyTheLastDay() {
        LikeBudget budget = new LikeBudget("");
        budget.add(0);
        budget.add(10 * HOUR);
        assertEquals(2, budget.count(20 * HOUR));
        assertEquals(1, budget.count(30 * HOUR));
        assertEquals(0, budget.count(40 * HOUR));
    }

    @Test
    public void warnsFromTheThresholdAndNotBefore() {
        LikeBudget budget = new LikeBudget("");
        for (int i = 0; i < LikeBudget.WARN_AT - 1; i++) budget.add(i);
        assertFalse(budget.shouldWarn(1000, 0));
        budget.add(1000);
        assertTrue(budget.shouldWarn(1000, 0));
    }

    @Test
    public void doesNotWarnAgainWithinHalfADay() {
        LikeBudget budget = new LikeBudget("");
        for (int i = 0; i < LikeBudget.WARN_AT; i++) budget.add(i);
        assertFalse(budget.shouldWarn(5 * HOUR, 1 * HOUR));
        assertTrue(budget.shouldWarn(14 * HOUR, 1 * HOUR));
    }

    @Test
    public void survivesBeingSavedAndReadBack() {
        LikeBudget budget = new LikeBudget("");
        budget.add(5);
        budget.add(9);
        LikeBudget again = new LikeBudget(budget.serialize());
        assertEquals(2, again.count(10));
    }

    @Test
    public void aDamagedSavedTextIsReadAsFarAsItGoes() {
        assertEquals(2, new LikeBudget("5,x,,9").count(10));
    }

    @Test
    public void aClockThatWentBackCountsAsDue() {
        assertTrue(LikeBudget.due(5, 100, HOUR));
        assertFalse(LikeBudget.due(100 + HOUR - 1, 100, HOUR));
        assertTrue(LikeBudget.due(100 + HOUR, 100, HOUR));
    }

    @Test
    public void keepsAtMostFourHundredTimes() {
        LikeBudget budget = new LikeBudget("");
        for (int i = 0; i < 500; i++) budget.add(i);
        assertEquals(400, budget.count(500));
    }
}
