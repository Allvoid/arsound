package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

public class WaveBatchTest {
    @Before
    public void english() {
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    public void snapshotKeepsSoundCloudTracksOnceInOrder() {
        List<String> list = Arrays.asList("soundcloud:tracks:3", null, "soundcloud:tracks:1", "soundcloud:tracks:3",
                "sc-local:file:x", "soundcloud:tracks:2");
        assertEquals(Arrays.asList("soundcloud:tracks:3", "soundcloud:tracks:1", "soundcloud:tracks:2"), WaveBatch.snapshot(list));
    }

    @Test
    public void snapshotIsACopyThatLaterChangesDoNotReach() {
        List<String> live = new ArrayList<>(Arrays.asList("soundcloud:tracks:1", "soundcloud:tracks:2"));
        List<String> taken = WaveBatch.snapshot(live);
        live.add("soundcloud:tracks:3");
        live.remove("soundcloud:tracks:1");
        assertEquals(Arrays.asList("soundcloud:tracks:1", "soundcloud:tracks:2"), taken);
    }

    @Test
    public void emptyAndNullListsGiveAnEmptySnapshot() {
        assertTrue(WaveBatch.snapshot(Collections.<String>emptyList()).isEmpty());
        assertTrue(WaveBatch.snapshot(null).isEmpty());
    }

    @Test
    public void trackIdIsTheNumberAfterTheLastColon() {
        assertEquals("123", WaveBatch.trackId("soundcloud:tracks:123"));
    }

    @Test
    public void likePlanGoesFromTheLastTrackAndSkipsLikedOnes() {
        List<String> urns = Arrays.asList("soundcloud:tracks:1", "soundcloud:tracks:2", "soundcloud:tracks:3", "soundcloud:tracks:4");
        HashSet<String> liked = new HashSet<>(Collections.singletonList("soundcloud:tracks:3"));
        assertEquals(Arrays.asList("soundcloud:tracks:4", "soundcloud:tracks:2", "soundcloud:tracks:1"),
                WaveBatch.likePlan(urns, liked));
    }

    @Test
    public void fractionIsClampedAndAnEmptyRunIsDone() {
        assertEquals(0.5f, WaveBatch.fraction(5, 10), 0.0001f);
        assertEquals(1f, WaveBatch.fraction(12, 10), 0.0001f);
        assertEquals(0f, WaveBatch.fraction(-1, 10), 0.0001f);
        assertEquals(1f, WaveBatch.fraction(0, 0), 0.0001f);
    }

    @Test
    public void summariesCountTheTracksAndNameWhatWentWrong() {
        assertEquals("Downloaded 14 of 17 (there before: 3), not available: 2, failed: 1",
                WaveBatch.downloadSummary(11, 3, 0, 2, 1, 17));
        assertEquals("Downloaded 5 of 5", WaveBatch.downloadSummary(5, 0, 0, 0, 0, 5));
        assertEquals("In likes: 17 of 17", WaveBatch.likedSummary(17, 17, false));
        assertTrue(WaveBatch.likedSummary(9, 17, true).startsWith("In likes: 9 of 17"));
        assertTrue(WaveBatch.likedSummary(9, 17, true).contains("stopped"));
        assertEquals("Added to \"Mix\": 15 of 17, the rest were there already", WaveBatch.addedSummary(15, 17, "Mix"));
        assertEquals("The list has no tracks", WaveBatch.addedSummary(0, 0, "Mix"));
    }
}
