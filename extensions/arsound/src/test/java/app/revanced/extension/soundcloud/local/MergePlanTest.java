package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class MergePlanTest {
    private static MergePlan editable(int serverTracks, int max, String... there) {
        return new MergePlan(Arrays.asList(there), serverTracks, true, max);
    }

    @Test
    public void tracksTheTargetHasAreSkipped() {
        MergePlan plan = editable(1, 10, "a");
        plan.offer("A", "a", false);
        plan.offer("B", "b", false);
        assertEquals(1, plan.duplicates());
        assertEquals(1, plan.incoming().size());
        assertEquals("b", plan.incoming().get(0).entry);
        assertTrue(plan.incoming().get(0).server);
    }

    @Test
    public void aTrackBroughtByAnEarlierPlaylistIsNotAddedTwice() {
        MergePlan plan = editable(0, 10);
        plan.offer("B", "b", false);
        plan.offer("B", "b", false);
        assertEquals(1, plan.incoming().size());
        assertEquals(1, plan.duplicates());
    }

    @Test
    public void filesStayOnThePhone() {
        MergePlan plan = editable(0, 10);
        plan.offer("F", "file:/x.mp3", true);
        assertFalse(plan.hasServerTracks());
        assertEquals(Collections.singletonList("file:/x.mp3"), plan.phoneEntries());
        assertEquals(0, plan.sentCount());
    }

    @Test
    public void everythingStaysOnThePhoneForAPlaylistOfSomebodyElse() {
        MergePlan plan = new MergePlan(Collections.<String>emptyList(), 0, false, 10);
        plan.offer("B", "b", false);
        assertFalse(plan.hasServerTracks());
        assertEquals(Collections.singletonList("b"), plan.phoneEntries());
    }

    @Test
    public void tracksOverTheLimitAreCountedNotAdded() {
        MergePlan plan = editable(2, 3);
        plan.offer("A", "a", false);
        plan.offer("B", "b", false);
        plan.offer("C", "c", false);
        assertEquals(1, plan.incoming().size());
        assertEquals(2, plan.overLimit());
    }

    @Test
    public void recheckSkipsWhatCameMeanwhileAndWhatNoLongerFits() {
        MergePlan plan = editable(0, 3);
        plan.offer("A", "a", false);
        plan.offer("B", "b", false);
        plan.offer("C", "c", false);
        // Meanwhile "a" came to the playlist and it has another track: 2 + "b" fits, "c" does not.
        plan.recheck(Arrays.asList("a", "x"));
        assertTrue(plan.incoming().get(0).skipped);
        assertFalse(plan.incoming().get(1).skipped);
        assertTrue(plan.incoming().get(2).skipped);
        assertEquals(1, plan.sentCount());
        assertEquals(1, plan.duplicates());
        assertEquals(1, plan.overLimit());
    }

    @Test
    public void filesBeforeTracksWhenAFileComesFirst() {
        MergePlan plan = editable(0, 10);
        plan.offer("F", "file:/x.mp3", true);
        plan.offer("B", "b", false);
        assertTrue(plan.filesBeforeTracks());

        MergePlan other = editable(0, 10);
        other.offer("B", "b", false);
        other.offer("F", "file:/x.mp3", true);
        assertFalse(other.filesBeforeTracks());
    }

    @Test
    public void nothingOfferedIsEmpty() {
        assertTrue(editable(0, 10).isEmpty());
    }
}
