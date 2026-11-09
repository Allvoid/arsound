package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QueueSwipeTest {
    @Test
    public void aShortSwipeDoesNothingAMiddleOnePlaysNextALongOneSavesTheQueue() {
        assertEquals(QueueSwipe.Action.NONE, QueueSwipe.Action.at(0f));
        assertEquals(QueueSwipe.Action.NONE, QueueSwipe.Action.at(QueueSwipe.NEXT_AT - 0.01f));
        assertEquals(QueueSwipe.Action.PLAY_NEXT, QueueSwipe.Action.at(QueueSwipe.NEXT_AT));
        assertEquals(QueueSwipe.Action.PLAY_NEXT, QueueSwipe.Action.at(QueueSwipe.SAVE_AT - 0.01f));
        assertEquals(QueueSwipe.Action.SAVE_QUEUE, QueueSwipe.Action.at(QueueSwipe.SAVE_AT));
        assertEquals(QueueSwipe.Action.SAVE_QUEUE, QueueSwipe.Action.at(0.8f));
    }
}
