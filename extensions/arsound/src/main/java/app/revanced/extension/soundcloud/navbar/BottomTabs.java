package app.revanced.extension.soundcloud.navbar;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.upsell.HidePaywallPatch;

/**
 * Takes the tabs the user chose out of SoundCloud's bottom bar and adds Arsound's "My Wave" tab after Home. A hidden tab is not removed: it moves to the end of the
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
            List<?> all = withWave(tabs);
            TabOrder.Arranged<Object> arranged = TabOrder.arrange(all, BottomTabs::placementOf);
            shown = arranged.shown;
            if (arranged.hiddenCount() == 0) return all;
            Logger.printInfo(() -> "Bottom bar: " + arranged.hiddenCount() + " of " + tabs.size() + " tabs hidden");
            return arranged.tabs;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not arrange the tabs of the bottom bar", ex);
            // The list goes back as it came, so the marks of a list arranged before do not fit it: no button is hidden.
            shown = java.util.Collections.emptyList();
            return tabs;
        }
    }

    /**
     * The tabs with "My Wave" right after Home, unless the user took it out. Arsound's own tab is simply left out then:
     * no deep link opens it, so it needs no hidden place.
     */
    private static List<?> withWave(List<?> tabs) {
        if (BoolSettings.HIDE_TAB_WAVE.get()) return tabs;
        return TabOrder.<Object>withExtra(tabs, new WaveNavigationTarget(),
                tab -> TabKind.of(tab) == TabKind.HOME, tab -> tab instanceof WaveNavigationTarget);
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
            restoreSelection(navigationBar, menu);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hide the buttons of the bottom bar", ex);
        }
    }

    /**
     * Injection point, in place of the comparison of the new list of tabs with the one applied before (in
     * {@code MainNavigationPresenter.applyNavigationTargets}): lists that differ only by the Upgrade tab, which Arsound
     * drops from the bar anyway, are the same. SoundCloud rebuilds the bar when the lists differ, and a rebuild must not
     * happen for nothing: it was what put the highlight back on Home while another tab was open (see
     * {@link #rememberSelection}). The server's list changes with the region (a VPN switched), so this came now and then.
     */
    public static boolean sameTabs(Object now, Object before) {
        try {
            if (now == before) return true;
            if (!(now instanceof List) || !(before instanceof List)) return Objects.equals(now, before);
            boolean dropUpgrade = Settings.isHideUpgradeTabEnabled();
            return TabOrder.sameWithout((List<?>) now, (List<?>) before, tab -> dropUpgrade && HidePaywallPatch.isUpgradeTab(tab));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not compare the tabs of the bottom bar", ex);
            return Objects.equals(now, before);
        }
    }

    /** The title of the tab selected when the bar was last about to be rebuilt; null when none was. */
    private static volatile CharSequence selectedBeforeRebuild;

    /**
     * Injection point: the start of {@code MainNavigationView.setupMenuItems}, before it clears the menu. Clearing the
     * menu makes Material forget the selected button and select the first (Home) while the screen of the tab that was
     * open stays: the bar says Home, SoundCloud thinks it is on Home, and a tap on Home does nothing. The selected tab is
     * remembered here and given back by {@link #hideButtons} once the menu is rebuilt.
     */
    public static void rememberSelection(View navigationBar) {
        selectedBeforeRebuild = null;
        try {
            int id = (Integer) navigationBar.getClass().getMethod("getSelectedItemId").invoke(navigationBar);
            Menu menu = (Menu) navigationBar.getClass().getMethod("getMenu").invoke(navigationBar);
            MenuItem item = menu.findItem(id);
            if (item != null) selectedBeforeRebuild = item.getTitle();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not remember the selected tab of the bottom bar", ex);
        }
    }

    /** Checks the button the user had open before the bar was rebuilt, without a tap: nothing is navigated. */
    private static void restoreSelection(View navigationBar, Menu menu) throws Exception {
        CharSequence title = selectedBeforeRebuild;
        selectedBeforeRebuild = null;
        if (title == null) return;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!title.equals(item.getTitle())) continue;
            int selected = (Integer) navigationBar.getClass().getMethod("getSelectedItemId").invoke(navigationBar);
            if (selected != item.getItemId()) {
                item.setChecked(true);
                Logger.printInfo(() -> "Bottom bar rebuilt: \"" + title + "\" kept selected");
            }
            return;
        }
    }
}
