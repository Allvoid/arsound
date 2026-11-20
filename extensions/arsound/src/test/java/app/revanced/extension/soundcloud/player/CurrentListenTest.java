package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

public class CurrentListenTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long START = 1_700_000_000_000L;

    @Test
    public void aListenShorterThanASecondIsNotCounted() {
        assertNull(ListeningStats.countable(null));
        assertNull(ListeningStats.countable(new ListenRecord(START, "soundcloud:tracks:1", 999)));
        assertNotNull(ListeningStats.countable(new ListenRecord(START, "soundcloud:tracks:1", 1_000)));
    }

    @Test
    public void theReviewWithTheListenInProgressSaysWhatTheScreenSays() {
        List<ListenRecord> history = new ArrayList<>();
        history.add(new ListenRecord(START, "soundcloud:tracks:1", 60_000));
        history.add(new ListenRecord(START + DAY, "soundcloud:tracks:2", 120_000));
        YearReview.Result finished = YearReview.build(history, 0, Long.MAX_VALUE, urn -> null, TimeZone.getTimeZone("UTC"));

        // The listen in progress is added the way ListeningStats.review adds it (the screen adds it too).
        List<ListenRecord> withCurrent = new ArrayList<>(history);
        withCurrent.add(ListeningStats.countable(new ListenRecord(START + 2 * DAY, "soundcloud:tracks:3", 30_000)));
        YearReview.Result result = YearReview.build(withCurrent, 0, Long.MAX_VALUE, urn -> null, TimeZone.getTimeZone("UTC"));

        assertEquals(180_000, finished.totalMs);
        assertEquals(210_000, result.totalMs);
        assertEquals(finished.plays + 1, result.plays);
    }
}
