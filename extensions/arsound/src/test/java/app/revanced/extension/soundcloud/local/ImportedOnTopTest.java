package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ImportedOnTopTest {
    private final LikePlacement placement = new ImportedOnTop();

    @Test
    public void importedFilesComeFirstInTheirOrder() {
        List<String> placed = placement.place(Arrays.asList("new", "old"), Arrays.asList("like 1", "like 2"));
        assertEquals(Arrays.asList("new", "old", "like 1", "like 2"), placed);
    }

    @Test
    public void emptySidesGiveTheOther() {
        assertEquals(Arrays.asList("a", "b"), placement.place(Collections.<String>emptyList(), Arrays.asList("a", "b")));
        assertEquals(Arrays.asList("a", "b"), placement.place(Arrays.asList("a", "b"), Collections.<String>emptyList()));
    }

    @Test
    public void inputsAreNotChanged() {
        List<String> stored = Arrays.asList("like");
        List<String> placed = placement.place(Arrays.asList("file"), stored);
        assertNotSame(stored, placed);
        assertEquals(Arrays.asList("like"), stored);
    }
}
