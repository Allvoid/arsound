package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StreamRepostsTest {
    @Test
    public void theSameListComesBackWhenNothingIsARepost() {
        List<String> items = Arrays.asList("post:a", "post:b");
        assertSame(items, StreamReposts.without(items, item -> item.startsWith("repost:")));
    }

    @Test
    public void repostsAreLeftOutAndTheOrderIsKept() {
        List<String> items = Arrays.asList("post:a", "repost:b", "post:c", "repost:d", "repost:e", "post:f");
        assertEquals(Arrays.asList("post:a", "post:c", "post:f"),
                StreamReposts.without(items, item -> item.startsWith("repost:")));
    }

    @Test
    public void aPageOfOnlyRepostsBecomesEmpty() {
        List<String> items = Arrays.asList("repost:a", "repost:b");
        assertEquals(Collections.emptyList(), StreamReposts.without(items, item -> true));
    }

    @Test
    public void aFirstRepostIsLeftOut() {
        List<String> items = Arrays.asList("repost:a", "post:b");
        assertEquals(Collections.singletonList("post:b"), StreamReposts.without(items, item -> item.startsWith("repost:")));
    }

    @Test
    public void anEmptyPageStaysAsItIs() {
        List<String> items = Collections.emptyList();
        assertSame(items, StreamReposts.without(items, item -> true));
    }
}
