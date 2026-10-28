package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ListenSessionTest {
    @Test
    public void recordFormatIsTheOneFilesAlreadyUse() {
        assertEquals("1000\tsoundcloud:tracks:1\t45000", new ListenRecord(1000, "soundcloud:tracks:1", 45000).format());
    }

    @Test
    public void recordParsesWhatItWrote() {
        ListenRecord record = ListenRecord.parse("1000\tsoundcloud:tracks:1\t45000");
        assertEquals(1000, record.start);
        assertEquals("soundcloud:tracks:1", record.urn);
        assertEquals(45000, record.playedMs);
    }

    @Test
    public void damagedRecordsAreNull() {
        assertNull(ListenRecord.parse("1000\turn"));
        assertNull(ListenRecord.parse("x\turn\t5"));
        assertNull(ListenRecord.parse("1\turn\ty"));
        assertNull(ListenRecord.parse(""));
        assertNull(ListenRecord.parse("1\turn\t2\t3"));
    }

    @Test
    public void lineDamagedOnlyInTheTimePlayedStillSaysWhenItStarted() {
        ListenRecord record = ListenRecord.parseStartAndUrn("1000\turn\toops");
        assertEquals(1000, record.start);
        assertEquals("urn", record.urn);
        assertNull(ListenRecord.parseStartAndUrn("oops\turn\t5"));
        assertNull(ListenRecord.parseStartAndUrn("1000\turn"));
    }

    @Test
    public void timePlayedCountsOnlyWhilePlaying() {
        ListenSession session = new ListenSession();
        session.begin("urn", 100);
        assertTrue(session.isActive());
        assertFalse(session.onPlayingChanged(true, 1_000));
        assertTrue(session.onPlayingChanged(false, 6_000));
        ListenRecord ended = session.end(9_000);
        assertEquals(100, ended.start);
        assertEquals(5_000, ended.playedMs);
        assertFalse(session.isActive());
    }

    @Test
    public void endingWhilePlayingCountsTheLastStretch() {
        ListenSession session = new ListenSession();
        session.begin("urn", 0);
        session.onPlayingChanged(true, 1_000);
        assertEquals(3_000, session.progress(4_000).playedMs);
        assertEquals(3_000, session.end(4_000).playedMs);
    }

    @Test
    public void startingTwiceOrStoppingTwiceChangesNothing() {
        ListenSession session = new ListenSession();
        session.begin("urn", 0);
        assertFalse(session.onPlayingChanged(false, 500));
        session.onPlayingChanged(true, 1_000);
        assertFalse(session.onPlayingChanged(true, 2_000));
        assertTrue(session.onPlayingChanged(false, 3_000));
        assertFalse(session.onPlayingChanged(false, 4_000));
        assertEquals(2_000, session.pendingMark().playedMs);
    }

    @Test
    public void resumedListenKeepsWhatPlayedBefore() {
        ListenSession session = new ListenSession();
        session.resume(new ListenRecord(50, "urn", 7_000));
        assertTrue(session.isOf("urn"));
        assertFalse(session.isOf("other"));
        session.onPlayingChanged(true, 100);
        assertEquals(7_500, session.progress(600).playedMs);
        assertEquals(50, session.progress(600).start);
    }

    @Test
    public void noListenNoProgress() {
        assertNull(new ListenSession().progress(1));
    }
}
