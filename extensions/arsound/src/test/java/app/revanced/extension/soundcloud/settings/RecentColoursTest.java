package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecentColoursTest {
    @Test
    public void aNewColourGoesFirst() {
        List<String> list = RecentColours.withAdded(Arrays.asList("#111111", "#222222"), "#333333");
        assertEquals(Arrays.asList("#333333", "#111111", "#222222"), list);
    }

    @Test
    public void aColourPickedAgainMovesUpInsteadOfRepeating() {
        List<String> list = RecentColours.withAdded(Arrays.asList("#111111", "#222222", "#333333"), "#222222");
        assertEquals(Arrays.asList("#222222", "#111111", "#333333"), list);
    }

    @Test
    public void capitalsAndSmallLettersAreOneColour() {
        List<String> list = RecentColours.withAdded(Arrays.asList("#AABBCC"), "#aabbcc");
        assertEquals(Arrays.asList("#AABBCC"), list);
    }

    @Test
    public void onlyTheNewestAreKept() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 12; i++) list = RecentColours.withAdded(list, String.format("#%06X", i + 1));
        assertEquals(RecentColours.MAX, list.size());
        assertEquals("#00000C", list.get(0));
        assertEquals("#000005", list.get(RecentColours.MAX - 1));
    }

    @Test
    public void somethingThatIsNotAColourChangesNothing() {
        List<String> before = Arrays.asList("#111111");
        assertEquals(before, RecentColours.withAdded(before, "red"));
        assertEquals(before, RecentColours.withAdded(before, "#12345"));
        assertEquals(before, RecentColours.withAdded(before, null));
    }

    @Test
    public void theStoredTextRoundTrips() {
        List<String> list = Arrays.asList("#FF5500", "#0D1117");
        assertEquals(list, RecentColours.parse(RecentColours.store(list)));
    }

    @Test
    public void aDamagedTextKeepsTheGoodPartsOnly() {
        assertEquals(Arrays.asList("#FF5500", "#00FF00"), RecentColours.parse("#ff5500,junk,,#FF5500, #00ff00 ,#12"));
        assertEquals(0, RecentColours.parse(null).size());
        assertEquals(0, RecentColours.parse("").size());
    }
}
