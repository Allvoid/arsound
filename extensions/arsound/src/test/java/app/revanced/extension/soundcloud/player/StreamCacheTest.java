package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StreamCacheTest {
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024 * MB;

    @Test
    public void aFullStorageLimitsTheCacheToTheSmallestOneWhateverWasMeasuredBefore() {
        // 0 free bytes is an answer, not "unknown": the old 50 GB of an earlier start must not be trusted.
        assertEquals(120 * MB, StreamCache.limitBySpace(0, 50 * GB, 0));
    }

    @Test
    public void whenTheSpaceCannotBeAskedTheLastMeasurementIsUsed() {
        assertEquals(9 * GB, StreamCache.limitBySpace(-1, 10 * GB, 0));
        assertTrue(StreamCache.limitBySpace(-1, -1, 0) < 0);
    }

    @Test
    public void theFreeSpaceAndTheCacheTogetherLessTheReserveAreTheLimit() {
        assertEquals(4 * GB, StreamCache.limitBySpace(3 * GB, 0, 2 * GB));
    }
}
