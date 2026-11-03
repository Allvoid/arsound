package app.revanced.extension.soundcloud.navbar;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * A search button in the top bar of the Library for the time the Search tab is out of the bottom bar: the Library is the
 * one tab that always stays, so the way into the search stays there. A tap picks the Search tab of the bottom bar as a
 * tap on its button would (the tab is only hidden, not removed).
 */
@SuppressWarnings("unused")
public final class LibrarySearchButton {
    private static final int ITEM_ID = 0x41535243;
    private static final int WAVE_ID = 0x41535244;

    private LibrarySearchButton() {
    }

    /** Injection point: the start of {@code LibraryFragment.onPrepareOptionsMenu(Menu)}. */
    public static void onPrepareMenu(Menu menu) {
        try {
            addWaveButton(menu);
            MenuItem existing = menu.findItem(ITEM_ID);
            if (!BoolSettings.HIDE_TAB_SEARCH.get()) {
                if (existing != null) menu.removeItem(ITEM_ID);
                return;
            }
            if (existing != null) return;
            MenuItem item = menu.add(Menu.NONE, ITEM_ID, Menu.NONE, Lang.text("Поиск", "Search"));
            int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_search");
            if (icon != 0) item.setIcon(icon);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(clicked -> {
                openSearch();
                return true;
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not add the search button to the Library", ex);
        }
    }

    /**
     * The wave icon, left of the search button: opens the "My Wave" screen. SoundCloud's bottom bar cannot take a tab of its
     * own (its screens are Compose pages), so the wave lives behind an icon in the top bar of the Library.
     */
    private static void addWaveButton(Menu menu) {
        MenuItem existing = menu.findItem(WAVE_ID);
        if (BoolSettings.HIDE_WAVE_BUTTON.get()) {
            if (existing != null) menu.removeItem(WAVE_ID);
            return;
        }
        if (existing != null) return;
        MenuItem item = menu.add(Menu.NONE, WAVE_ID, Menu.NONE, Lang.text("Моя волна", "My Wave"));
        int icon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_wave");
        if (icon != 0) item.setIcon(icon);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(clicked -> {
            Activity activity = UpdateChecker.resumedActivity();
            if (activity != null) activity.startActivity(app.revanced.extension.soundcloud.settings.WaveScreenLauncher.intent(activity));
            return true;
        });
    }

    /** Picks the Search tab of the bottom bar, found as the view that has a menu with the Search tab in it. */
    private static void openSearch() {
        try {
            Activity activity = UpdateChecker.resumedActivity();
            if (activity == null) return;
            View bar = findNavigationBar(activity.getWindow().getDecorView());
            if (bar == null) {
                Logger.printInfo(() -> "The bottom bar was not found: no search");
                return;
            }
            Menu menu = (Menu) bar.getClass().getMethod("getMenu").invoke(bar);
            // The Search tab is the hidden one whose title says so; the bar picks it as if its button was tapped.
            for (int i = 0; i < menu.size(); i++) {
                MenuItem tab = menu.getItem(i);
                if (!tab.isVisible() && isSearch(tab.getTitle())) {
                    bar.getClass().getMethod("setSelectedItemId", int.class).invoke(bar, tab.getItemId());
                    return;
                }
            }
            Logger.printInfo(() -> "No hidden Search tab in the bottom bar");
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the search", ex);
        }
    }

    private static boolean isSearch(CharSequence title) {
        if (title == null) return false;
        String lower = title.toString().toLowerCase(java.util.Locale.ROOT);
        return lower.equals("поиск") || lower.equals("search");
    }

    /** The bottom bar is the Material navigation bar of the activity: the view that has {@code getSelectedItemId}. */
    private static View findNavigationBar(View view) {
        if (hasSelectedItem(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findNavigationBar(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasSelectedItem(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.google.android.material.navigation.NavigationBarView")) return true;
        }
        return false;
    }
}
