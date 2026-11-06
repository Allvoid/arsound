package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class LikePlanTest {
    @Test
    public void theLastTrackIsLikedFirstSoTheFirstEndsOnTop() {
        assertEquals(Arrays.asList("c", "b", "a"), LikePlan.of(Arrays.asList("a", "b", "c"), Collections.emptySet()));
    }

    @Test
    public void aTrackAlreadyLikedIsLeftAlone() {
        List<String> plan = LikePlan.of(Arrays.asList("a", "b", "c"), new HashSet<>(Arrays.asList("b")));
        assertEquals(Arrays.asList("c", "a"), plan);
    }

    @Test
    public void nothingToLikeWhenAllAreLiked() {
        assertTrue(LikePlan.of(Arrays.asList("a"), new HashSet<>(Arrays.asList("a"))).isEmpty());
    }
}
