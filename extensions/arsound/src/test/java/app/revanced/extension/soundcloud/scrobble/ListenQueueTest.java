package app.revanced.extension.soundcloud.scrobble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ListenQueueTest {
    private static ScrobbleListen listen(int n) {
        return new ScrobbleListen("Artist " + n, "Song " + n, 1_700_000_000L + n, 180_000);
    }

    @Test
    public void listensLeaveInTheOrderTheyCame() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "me");
        queue.add(listen(2), "me");
        assertEquals("Song 1", queue.peek().title);
        assertTrue(queue.removeFront(listen(1)));
        assertEquals("Song 2", queue.peek().title);
    }

    @Test
    public void onlyTheFrontOneIsRemovedWhenItWasTheOneSent() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "me");
        assertFalse(queue.removeFront(listen(2)));
        assertEquals(1, queue.size());
        queue.clear();
        assertFalse(queue.removeFront(listen(1)));
    }

    @Test
    public void atMostAHundredWaitAndTheOldestGoFirst() {
        ListenQueue queue = new ListenQueue();
        for (int i = 1; i <= 130; i++) queue.add(listen(i), "me");
        assertEquals(ListenQueue.MAX, queue.size());
        assertEquals("Song 31", queue.peek().title);
    }

    @Test
    public void theOwnerIsKeptWhileSomethingWaitsAndForgottenAfter() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "me");
        assertEquals("me", queue.owner());
        queue.removeFront(listen(1));
        assertEquals("", queue.owner());
    }

    @Test
    public void listensOfAnotherAccountAreLetGo() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "first");
        assertFalse(queue.keepOnlyFor("first"));
        assertEquals(1, queue.size());
        assertTrue(queue.keepOnlyFor("second"));
        assertTrue(queue.isEmpty());
    }

    @Test
    public void aListenOfANewAccountReplacesWhatWaitedForTheOldOne() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "first");
        queue.add(listen(2), "first");
        queue.add(listen(3), "second");
        assertEquals(1, queue.size());
        assertEquals("Song 3", queue.peek().title);
        assertEquals("second", queue.owner());
    }

    @Test
    public void oldListensAreDroppedForLastFm() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "me");
        queue.add(listen(500), "me");
        queue.dropOlderThan(1_700_000_100L);
        assertEquals(1, queue.size());
        assertEquals("Song 500", queue.peek().title);
        queue.dropOlderThan(Long.MAX_VALUE);
        assertTrue(queue.isEmpty());
        assertEquals("", queue.owner());
    }

    @Test
    public void theQueueSurvivesTheDisk() {
        ListenQueue queue = new ListenQueue();
        queue.add(new ScrobbleListen("Тест & Co", "Песня \"1\"", 1_700_000_001L, 123_000), "me");
        queue.add(listen(2), "me");
        ListenQueue read = ListenQueue.fromJson(queue.toJson().toString());
        assertEquals(2, read.size());
        assertEquals("me", read.owner());
        assertEquals("Тест & Co", read.peek().artist);
        assertEquals("Песня \"1\"", read.peek().title);
        assertEquals(1_700_000_001L, read.peek().listenedAt);
        assertEquals(123_000, read.peek().durationMs);
    }

    @Test
    public void damagedFilesGiveAnEmptyQueue() {
        assertTrue(ListenQueue.fromJson(null).isEmpty());
        assertTrue(ListenQueue.fromJson("").isEmpty());
        assertTrue(ListenQueue.fromJson("not json").isEmpty());
        assertTrue(ListenQueue.fromJson("{\"o\":\"x\"}").isEmpty());
    }

    @Test
    public void damagedEntriesAreSkippedAndTheRestIsKept() {
        String text = "{\"o\":\"me\",\"l\":[{\"a\":\"A\",\"t\":\"\",\"at\":5},{\"a\":\"B\",\"t\":\"T\",\"at\":0},"
                + "{\"a\":\"C\",\"t\":\"U\",\"at\":1700000000,\"d\":1000},7]}";
        ListenQueue queue = ListenQueue.fromJson(text);
        assertEquals(1, queue.size());
        assertEquals("C", queue.peek().artist);
        assertEquals("me", queue.owner());
        assertNull(ListenQueue.fromJson("{\"o\":\"me\",\"l\":[]}").peek());
    }

    @Test
    public void theOwnerDigestHidesTheCredential() {
        String digest = ListenQueue.ownerOf("secret-token");
        assertEquals(32, digest.length());
        assertFalse(digest.contains("secret"));
        assertEquals(digest, ListenQueue.ownerOf("secret-token"));
        assertFalse(digest.equals(ListenQueue.ownerOf("other-token")));
        assertEquals("", ListenQueue.ownerOf(""));
    }

    @Test
    public void droppingOldListensTellsWhetherAnythingWasDropped() {
        ListenQueue queue = new ListenQueue();
        queue.add(listen(1), "me");
        // Nothing is older than the bound: the queue is unchanged, so there is nothing to write.
        assertFalse(queue.dropOlderThan(1_700_000_000L));
        assertTrue(queue.dropOlderThan(1_700_000_002L));
        assertTrue(queue.isEmpty());
        assertFalse(queue.dropOlderThan(1_800_000_000L));
    }
}
