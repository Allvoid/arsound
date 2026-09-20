package app.revanced.extension.soundcloud.upsell;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

@SuppressWarnings("unused")
public final class HidePaywallPatch {
    /**
     * Injection point. Called with the intent that opens the subscription offer screen.
     *
     * @return An intent to an invisible screen that closes right away, so the offer screen never shows,
     * or the original intent if the offers are not hidden.
     */
    public static Intent filterPaywallIntent(Intent intent) {
        if (intent == null || !Settings.isHideSubscriptionOffersEnabled()) return intent;

        ComponentName component = intent.getComponent();
        if (component == null) return intent;

        Logger.printDebug(() -> "Replacing subscription offer screen intent");
        Intent replacement = new Intent();
        replacement.setComponent(new ComponentName(component.getPackageName(), EmptyActivity.class.getName()));
        replacement.addFlags(intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        replacement.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return replacement;
    }

    /**
     * Injection point. Fallback for offer screens opened without the patched intent builders.
     *
     * @return True if the screen was closed and its setup must be skipped.
     */
    public static boolean hidePaywall(Activity activity) {
        if (!Settings.isHideSubscriptionOffersEnabled()) return false;

        activity.finish();
        activity.overridePendingTransition(0, 0);
        return true;
    }

    /**
     * Injection point. Called with the bottom bar tabs before the menu is built.
     *
     * @return The tabs without the Upgrade tab if it is hidden, otherwise the original list.
     */
    public static List<?> filterNavigationTabs(List<?> tabs) {
        Logger.printDebug(() -> "Bottom bar tabs before: " + names(tabs)
                + ", hiding the Upgrade tab: " + Settings.isHideUpgradeTabEnabled());
        if (tabs == null || !Settings.isHideUpgradeTabEnabled()) return tabs;

        List<Object> filtered = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            String name = tab == null ? "" : tab.getClass().getSimpleName();
            if (name.equals("GoNavigationTarget") || name.equals("GoPlusNavigationTarget")
                    || name.equals("ProUnlimitedNavigationTarget")) {
                Logger.printDebug(() -> "Hiding bottom bar tab " + name);
                continue;
            }
            filtered.add(tab);
        }
        Logger.printDebug(() -> "Bottom bar tabs after: " + names(filtered));
        return filtered;
    }

    /**
     * Injection point. Called when a bottom bar tab is tapped, before the screen is switched.
     * <p>
     * SoundCloud addresses a tab by its position in the tab list, so this records which position was
     * tapped and which tab it landed on. A tab that opens the screen of another one shows up here.
     */
    public static void logNavigationTap(Object navigationView, android.view.MenuItem item) {
        try {
            int tapped = item == null ? -1 : item.getItemId();
            CharSequence title = item == null ? "" : item.getTitle();
            int selected = selectedItemId(navigationView);
            Logger.printDebug(() -> "Bottom bar tap: position " + tapped + " (" + title + ")"
                    + ", position now selected " + selected);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not log the bottom bar tap", ex);
        }
    }

    /** The bottom bar keeps the selected tab as the position it had when the bar was built. */
    private static int selectedItemId(Object mainNavigationView) throws Exception {
        if (mainNavigationView == null) return -1;

        java.lang.reflect.Field field = mainNavigationView.getClass().getDeclaredField("navigationView");
        field.setAccessible(true);
        Object bar = field.get(mainNavigationView);
        if (bar == null) return -1;

        return (int) bar.getClass().getMethod("getSelectedItemId").invoke(bar);
    }

    private static String name(Object target) {
        return target == null ? "none" : target.getClass().getSimpleName();
    }

    private static String names(List<?> tabs) {
        if (tabs == null) return "none";

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            if (i != 0) text.append(", ");
            text.append(i).append('=').append(name(tabs.get(i)));
        }
        return text.toString();
    }

    /**
     * Injection point. Called with SoundCloud's answer to whether the library may show its
     * "Get SoundCloud Go+" banner.
     *
     * @return False while subscription offers are hidden, otherwise the original answer.
     */
    public static boolean filterUpsellBanner(boolean canDisplay) {
        if (!canDisplay || !Settings.isHideSubscriptionOffersEnabled()) return canDisplay;

        Logger.printDebug(() -> "Hiding the subscription banner in the library");
        return false;
    }

    /**
     * MoEngage in-app messages are marketing popups, mostly subscription offers.
     */
    public static boolean hideInAppMessages() {
        return Settings.isHideSubscriptionOffersEnabled();
    }
}
