package app.revanced.extension.soundcloud.navbar;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import app.revanced.extension.soundcloud.navbar.TabOrder.Placement;

public class TabOrderTest {
    private static final List<String> TABS = Arrays.asList("home", "feed", "search", "library");

    private static TabOrder.Arranged<String> arrange(List<String> tabs, String... hidden) {
        Set<String> away = new HashSet<>(Arrays.asList(hidden));
        return TabOrder.arrange(tabs, tab -> tab.equals("upgrade") ? Placement.LAST
                : away.contains(tab) ? Placement.HIDDEN : Placement.STAYS);
    }

    @Test
    public void nothingHiddenKeepsTheOrder() {
        TabOrder.Arranged<String> arranged = arrange(TABS);
        assertEquals(TABS, arranged.tabs);
        assertEquals(0, arranged.hiddenCount());
    }

    @Test
    public void hiddenTabsGoToTheEndInTheirOwnOrder() {
        TabOrder.Arranged<String> arranged = arrange(TABS, "home", "search");
        assertEquals(Arrays.asList("feed", "library", "home", "search"), arranged.tabs);
        assertEquals(Arrays.asList(true, true, false, false), arranged.shown);
    }

    @Test
    public void onlyTheLibraryCanStay() {
        TabOrder.Arranged<String> arranged = arrange(TABS, "home", "feed", "search");
        assertEquals("library", arranged.tabs.get(0));
        assertEquals(3, arranged.hiddenCount());
    }

    @Test
    public void theUpgradeTabStaysLastSoTheOthersDoNotShiftWhenItArrives() {
        List<String> withUpgrade = Arrays.asList("home", "feed", "search", "library", "upgrade");
        TabOrder.Arranged<String> before = arrange(TABS, "home", "search");
        TabOrder.Arranged<String> after = arrange(withUpgrade, "home", "search");
        assertEquals(Arrays.asList("feed", "library", "home", "search", "upgrade"), after.tabs);
        assertEquals(before.tabs, after.tabs.subList(0, 4));
        assertEquals(true, after.shown.get(4));
    }

    @Test
    public void arrangingTwiceChangesNothing() {
        TabOrder.Arranged<String> once = arrange(TABS, "feed");
        TabOrder.Arranged<String> twice = arrange(once.tabs, "feed");
        assertEquals(once.tabs, twice.tabs);
        assertEquals(once.shown, twice.shown);
    }

    @Test
    public void theInputIsNotChanged() {
        List<String> input = Arrays.asList("home", "library");
        TabOrder.Arranged<String> arranged = arrange(input, "home");
        assertEquals(Arrays.asList("home", "library"), input);
        assertEquals(Arrays.asList("library", "home"), arranged.tabs);
    }

    @Test
    public void theWaveComesRightAfterHome() {
        List<String> tabs = TabOrder.withExtra(TABS, "wave", "home"::equals, "wave"::equals);
        assertEquals(Arrays.asList("home", "wave", "feed", "search", "library"), tabs);
    }

    @Test
    public void withoutHomeTheWaveComesFirst() {
        List<String> tabs = TabOrder.withExtra(Arrays.asList("feed", "library"), "wave", "home"::equals, "wave"::equals);
        assertEquals(Arrays.asList("wave", "feed", "library"), tabs);
    }

    @Test
    public void theWaveIsNotAddedTwice() {
        List<String> once = TabOrder.withExtra(TABS, "wave", "home"::equals, "wave"::equals);
        assertEquals(once, TabOrder.withExtra(once, "wave", "home"::equals, "wave"::equals));
    }

    @Test
    public void aHiddenHomeLeavesTheWaveFirstOfTheShownTabs() {
        List<String> tabs = TabOrder.withExtra(TABS, "wave", "home"::equals, "wave"::equals);
        TabOrder.Arranged<String> arranged = arrange(tabs, "home");
        assertEquals(Arrays.asList("wave", "feed", "search", "library", "home"), arranged.tabs);
    }
}
