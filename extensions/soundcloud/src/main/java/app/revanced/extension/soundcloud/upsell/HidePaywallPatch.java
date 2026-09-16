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
        return filtered;
    }

    /**
     * MoEngage in-app messages are marketing popups, mostly subscription offers.
     */
    public static boolean hideInAppMessages() {
        return Settings.isHideSubscriptionOffersEnabled();
    }
}
