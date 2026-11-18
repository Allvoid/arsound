package app.revanced.extension.soundcloud.navbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class TabChoiceTest {
    @Test
    public void anEmptyTextMeansTheBarAsSoundCloudHasIt() {
        assertTrue(TabChoice.parse("").isEmpty());
        assertTrue(TabChoice.parse(null).isEmpty());
    }

    @Test
    public void missingKeysAreAddedAtTheEndAndUnknownOnesDropped() {
        assertEquals(Arrays.asList("library", "home", "wave", "feed", "search"), TabChoice.parse("library, nonsense,home,library"));
    }

    @Test
    public void joinAndParseGoBack() {
        java.util.List<String> order = Arrays.asList("search", "library", "home", "feed", "wave");
        assertEquals(order, TabChoice.parse(TabChoice.join(order)));
    }

    @Test
    public void codesOfTheStartChoiceNameTheKeys() {
        assertEquals("", TabChoice.keyOfCode(0));
        assertEquals("home", TabChoice.keyOfCode(1));
        assertEquals("library", TabChoice.keyOfCode(5));
        assertEquals("", TabChoice.keyOfCode(9));
    }
}
