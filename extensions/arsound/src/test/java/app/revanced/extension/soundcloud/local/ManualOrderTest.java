package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ManualOrderTest {
    private static final List<String> SAVED = Arrays.asList("b", "a");

    @Test
    public void arrangedItemsFollowTheSavedOrder() {
        ManualOrder order = new ManualOrder("k", ManualOrder.Unknown.LAST);
        assertEquals(Arrays.asList("b", "a"), order.sort(Arrays.asList("a", "b"), SAVED, item -> item));
    }

    @Test
    public void newItemsGoLastKeepingTheirOrder() {
        ManualOrder order = new ManualOrder("k", ManualOrder.Unknown.LAST);
        assertEquals(Arrays.asList("b", "a", "y", "x"), order.sort(Arrays.asList("y", "a", "x", "b"), SAVED, item -> item));
    }

    @Test
    public void newItemsGoFirstWhenAsked() {
        ManualOrder order = new ManualOrder("k", ManualOrder.Unknown.FIRST);
        assertEquals(Arrays.asList("y", "x", "b", "a"), order.sort(Arrays.asList("a", "y", "b", "x"), SAVED, item -> item));
    }

    @Test
    public void anItemWithoutAKeyCountsAsNew() {
        ManualOrder order = new ManualOrder("k", ManualOrder.Unknown.FIRST);
        List<String> sorted = order.sort(Arrays.asList("a", null, "b"), SAVED, item -> item);
        assertEquals(Arrays.asList(null, "b", "a"), sorted);
    }

    @Test
    public void eachKeyIsReadOnce() {
        ManualOrder order = new ManualOrder("k", ManualOrder.Unknown.LAST);
        int[] reads = new int[1];
        List<String> items = Arrays.asList("y", "a", "x", "b", "z", "q", "w");
        order.sort(items, SAVED, item -> {
            reads[0]++;
            return item;
        });
        assertEquals(items.size(), reads[0]);
    }
}
