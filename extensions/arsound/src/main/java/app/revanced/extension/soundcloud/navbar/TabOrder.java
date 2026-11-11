package app.revanced.extension.soundcloud.navbar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Puts the tabs of the bottom bar in the order the app is built with: the tabs that stay first, in their own order, the
 * hidden ones after them, and the tab that comes by itself (Upgrade) at the very end. The first tab is the one the app
 * starts on and goes back to, and a tab's position is its number everywhere in SoundCloud, so hiding a tab by moving it
 * to the end (and hiding its button) keeps every number true. SoundCloud appends the Upgrade tab to the list once the
 * server has answered: it stays last so that the numbers of the other tabs do not shift when it arrives. No Android in
 * here; used by {@link BottomTabs}.
 */
final class TabOrder {
    private TabOrder() {
    }

    /** Where a tab goes; the order of the constants is the order in the list. */
    enum Placement {
        STAYS(true), HIDDEN(false), LAST(true);

        final boolean shown;

        Placement(boolean shown) {
            this.shown = shown;
        }
    }

    /** The result of {@link #arrange}: the new order and, per place of it, whether the tab's button is shown. */
    static final class Arranged<T> {
        final List<T> tabs;
        final List<Boolean> shown;

        Arranged(List<T> tabs, List<Boolean> shown) {
            this.tabs = tabs;
            this.shown = shown;
        }

        int hiddenCount() {
            int count = 0;
            for (boolean tabShown : shown) if (!tabShown) count++;
            return count;
        }
    }

    /**
     * The tabs with {@code extra} put right after the first tab {@code after} accepts, or first when none does. The
     * list comes back as it was when it holds a tab {@code same} accepts already, so a list arranged twice does not get
     * the tab twice.
     */
    static <T> List<T> withExtra(List<? extends T> tabs, T extra, Predicate<? super T> after, Predicate<? super T> same) {
        List<T> result = new ArrayList<>(tabs.size() + 1);
        for (T tab : tabs) {
            if (same.test(tab)) return new ArrayList<>(tabs);
        }
        boolean placed = false;
        for (T tab : tabs) {
            result.add(tab);
            if (!placed && after.test(tab)) {
                result.add(extra);
                placed = true;
            }
        }
        if (!placed) result.add(0, extra);
        return result;
    }

    /** The tabs in the order of {@link Placement}, each group in the order it came. The same input always gives the same output. */
    static <T> Arranged<T> arrange(List<? extends T> tabs, Function<? super T, Placement> placement) {
        List<T> ordered = new ArrayList<>(tabs.size());
        List<Boolean> shown = new ArrayList<>(tabs.size());
        for (Placement wanted : Placement.values()) {
            for (T tab : tabs) {
                if (placement.apply(tab) != wanted) continue;
                ordered.add(tab);
                shown.add(wanted.shown);
            }
        }
        return new Arranged<>(ordered, shown);
    }

    /** Whether two lists of tabs are the same once the tabs {@code ignored} accepts are left out of both. */
    static boolean sameWithout(List<?> a, List<?> b, Predicate<Object> ignored) {
        List<Object> left = new ArrayList<>(a.size());
        for (Object tab : a) if (!ignored.test(tab)) left.add(tab);
        List<Object> right = new ArrayList<>(b.size());
        for (Object tab : b) if (!ignored.test(tab)) right.add(tab);
        return left.equals(right);
    }
}
