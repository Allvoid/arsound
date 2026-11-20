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
    /** The key ({@link #keyOf}) of the tab at each place of the list arranged last, null for a tab with none. */
    private static volatile List<String> keys = Collections.emptyList();
    /**
     * The intent of the launch the start tab was opened for (weakly held). Back closes the activity and the process stays
     * cached, so "once per run" was once per process: the next tap on the launcher opened the first tab, against the
     * setting. A new launch has a new intent; a turned phone makes a new activity with the same intent and is left alone.
     */
    private static java.lang.ref.WeakReference<Object> appliedFor = new java.lang.ref.WeakReference<>(null);

    /** Whether a start from the launcher with this intent is a launch the start tab has not been opened for yet. */
    static boolean isNewLaunch(Object appliedBefore, Object intent) {
        return intent != null && intent != appliedBefore;
    }

    /** The extra SoundCloud's launcher screen puts on the intent it hands to the main screen. */
    private static final String FROM_LAUNCHER_EXTRA = "EXTRA_FROM_LAUNCHER";

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
            List<?> all = TabOrder.reorder(withWave(tabs), BottomTabs::keyOf, TabChoice.order());
            TabOrder.Arranged<Object> arranged = TabOrder.arrange(all, BottomTabs::placementOf);
            // The keys and marks describe the list handed back, place by place. With nothing hidden that is the list as
            // it came, which can differ from the arranged order (the Upgrade tab may sit elsewhere in it).
            boolean nothingHidden = arranged.hiddenCount() == 0;
            List<?> result = nothingHidden ? all : arranged.tabs;
            List<Boolean> marks = new java.util.ArrayList<>();
            List<String> arrangedKeys = new java.util.ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                arrangedKeys.add(keyOf(result.get(i)));
                marks.add(nothingHidden || arranged.shown.get(i));
            }
            shown = marks;
            keys = arrangedKeys;
            if (nothingHidden) return all;
            Logger.printInfo(() -> "Bottom bar: " + arranged.hiddenCount() + " of " + tabs.size() + " tabs hidden");
            return arranged.tabs;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not arrange the tabs of the bottom bar", ex);
            // The list goes back as it came, so the marks of a list arranged before do not fit it: no button is hidden.
            shown = java.util.Collections.emptyList();
            keys = java.util.Collections.emptyList();
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

    /** The key the user's order and start tab name a tab by; null for a tab the user cannot place (the create button, Upgrade). */
    static String keyOf(Object tab) {
        if (tab == null) return null;
        if (tab instanceof WaveNavigationTarget) return TabChoice.WAVE;
        TabKind kind = TabKind.of(tab);
        if (kind == TabKind.HOME) return TabChoice.HOME;
        if (kind == TabKind.FEED) return TabChoice.FEED;
        if (kind == TabKind.SEARCH) return TabChoice.SEARCH;
        if (tab.getClass().getSimpleName().contains("LibraryNavigationTarget")) return TabChoice.LIBRARY;
        return null;
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
            openStartTab(navigationBar, menu, places);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hide the buttons of the bottom bar", ex);
        }
    }

    /**
     * The tab the user chose to start on, opened once per launch of the app (per intent) and only when the app was started from the
     * launcher: a link or a shortcut already says where to go. The first tab of the bar is what the app opens by itself;
     * here another one is selected by its button, as a tap on it would.
     * <p>
     * Known limit: when Android brings the app back after its process was killed, the restored screen (say Library) looks
     * the same as a launch from here. The hook cannot see the saved instance state, so the start tab replaces that screen
     * once, 400 ms after the bar is built.
     */
    private static void openStartTab(View navigationBar, Menu menu, List<Boolean> places) throws Exception {
        android.app.Activity screen = activityOf(navigationBar);
        Object intent = screen == null ? null : screen.getIntent();
        if (!isNewLaunch(appliedFor.get(), intent)) return;
        appliedFor = new java.lang.ref.WeakReference<>(intent);
        String wanted = TabChoice.start();
        if (wanted.isEmpty() || !launchedFromLauncher(navigationBar)) return;
        List<String> placed = keys;
        for (int i = 0; i < menu.size() && i < placed.size() && i < places.size(); i++) {
            if (!wanted.equals(placed.get(i)) || !places.get(i)) continue;
            int id = menu.getItem(i).getItemId();
            int selected = (Integer) navigationBar.getClass().getMethod("getSelectedItemId").invoke(navigationBar);
            if (selected == id) return;
            // After the bar has its listeners: the tap must reach the navigation.
            navigationBar.postDelayed(() -> {
                try {
                    navigationBar.getClass().getMethod("setSelectedItemId", int.class).invoke(navigationBar, id);
                    Logger.printInfo(() -> "Bottom bar: started on the tab \"" + wanted + "\"");
                } catch (Throwable ex) {
                    Logger.printException(() -> "Could not open the start tab", ex);
                }
            }, 400);
            return;
        }
    }

    private static android.app.Activity activityOf(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity)) {
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return context instanceof android.app.Activity ? (android.app.Activity) context : null;
    }

    private static boolean launchedFromLauncher(View view) {
        android.app.Activity context = activityOf(view);
        if (context == null) return false;
        android.content.Intent intent = context.getIntent();
        if (intent == null) return false;
        android.os.Bundle extras = intent.getExtras();
        return isLauncherStart(intent.getAction(), intent.getData() != null,
                intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER), extras != null && !extras.isEmpty(),
                intent.getBooleanExtra(FROM_LAUNCHER_EXTRA, false));
    }

    /**
     * Whether an intent is a start from the launcher: no data address, and no action or the main one. The launcher screen
     * of SoundCloud hands a plain start on to the main screen without any action (checked on the emulator); a link or a
     * shortcut carries a data address or its own action. A start with neither but with extras and without the launcher
     * category (a notification or a widget that only carries extras) already says where to go, as a link does.
     * The exception is the launcher screen's own mark: {@code LaunchLogic} copies the extras of its intent, with
     * {@code EXTRA_FROM_LAUNCHER = true} added, onto the intent of the main screen and drops the action and the category
     * (seen on the emulator: {@code flg=0x1000c000, has extras}), so that mark is what tells a tap on the launcher.
     */
    static boolean isLauncherStart(String action, boolean hasData, boolean launcherCategory, boolean hasExtras,
                                   boolean fromLauncherExtra) {
        if (hasData) return false;
        if (action != null && !android.content.Intent.ACTION_MAIN.equals(action)) return false;
        return launcherCategory || fromLauncherExtra || !hasExtras;
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
