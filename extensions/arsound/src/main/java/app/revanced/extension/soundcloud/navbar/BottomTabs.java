package app.revanced.extension.soundcloud.navbar;

import android.view.Menu;
import android.view.View;

import java.util.Collections;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.upsell.HidePaywallPatch;

/**
 * Takes the tabs the user chose out of SoundCloud's bottom bar. A hidden tab is not removed: it moves to the end of the
 * list the app is built from and its button is hidden, so the tab's number stays true everywhere SoundCloud uses it and
 * the first visible tab is the one the app starts on and goes back to. A deep link or a shortcut that opens the hidden
 * tab still works; only the button is gone.
 * <p>
 * The list is arranged where the tab provider hands it over ({@link #arrange}) and the buttons are hidden after the bar
 * built its menu ({@link #hideButtons}).
 */
@SuppressWarnings("unused")
public final class BottomTabs {
    /** Whether the button of each place of the list arranged last is shown; empty before any list. */
    private static volatile List<Boolean> shown = Collections.emptyList();

    private BottomTabs() {
    }

    /**
     * Injection point (also called by {@code HidePaywallPatch.filterNavigationTabs}). The tabs of the bar in the order to
     * build it with: those that stay, then the hidden ones, then the Upgrade tab. Library is never hidden; with nothing
     * hidden the list is returned as it came.
     */
    public static List<?> arrange(List<?> tabs) {
        if (tabs == null) return null;
        try {
            TabOrder.Arranged<Object> arranged = TabOrder.arrange(tabs, BottomTabs::placementOf);
            shown = arranged.shown;
            if (arranged.hiddenCount() == 0) return tabs;
            Logger.printInfo(() -> "Bottom bar: " + arranged.hiddenCount() + " of " + tabs.size() + " tabs hidden");
            return arranged.tabs;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not arrange the tabs of the bottom bar", ex);
            return tabs;
        }
    }

    private static TabOrder.Placement placementOf(Object tab) {
        if (HidePaywallPatch.isUpgradeTab(tab)) return TabOrder.Placement.LAST;
        TabKind kind = TabKind.of(tab);
        return kind != null && kind.isHidden() ? TabOrder.Placement.HIDDEN : TabOrder.Placement.STAYS;
    }

    /**
     * Injection point: the end of {@code MainNavigationView.setupMenuItems}. Hides the buttons of the tabs that
     * {@link #arrange} marked hidden; the buttons of the others are left as SoundCloud made them.
     */
    public static void hideButtons(View navigationBar) {
        try {
            List<Boolean> places = shown;
            Menu menu = (Menu) navigationBar.getClass().getMethod("getMenu").invoke(navigationBar);
            for (int i = 0; i < menu.size() && i < places.size(); i++) {
                if (!places.get(i)) menu.getItem(i).setVisible(false);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hide the buttons of the bottom bar", ex);
        }
    }
}
