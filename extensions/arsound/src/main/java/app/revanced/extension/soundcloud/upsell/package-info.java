/**
 * Subscription offers: hides what sells Go / Go+ inside the mod.
 *
 * <p>User view: the subscription offer screen closes at once, marketing popups and the library banner are not shown,
 * and the Upgrade tab of the bottom bar either explains why a subscription cannot be bought here or is removed.
 *
 * <p>Entry points (the Kotlin side is {@code upsell/*Patch.kt}; the descriptors are in {@code upsell/UpsellHooks.kt}):
 * <ul>
 * <li>{@code HidePaywallPatch.filterPaywallIntent(Intent)} and {@code hidePaywall(Activity)} - the offer screen is
 * replaced by {@code EmptyActivity}, an invisible activity that the patch declares in the manifest.</li>
 * <li>{@code HidePaywallPatch.filterNavigationTabs(List)} and {@code logNavigationTap(...)} - bottom bar tabs (the
 * Go, Go+ and Pro Unlimited targets are removed when the Upgrade tab is hidden).</li>
 * <li>{@code HidePaywallPatch.filterUpsellBanner(boolean)} and {@code hideInAppMessages()} - banners and popups.</li>
 * <li>{@code UpgradeTabPage.createView(...)} and {@code isPage(View)} - the explanation page that stands in the
 * tab.</li>
 * </ul>
 *
 * <p>Settings (both in {@code settings.Settings}): {@code hide_subscription_offers} (default on; one switch for the
 * screen, banners and popups) and {@code hide_upgrade_tab} (default off; written to disk at once because the app
 * restarts right after it).
 *
 * <p>Gotchas: the names above are called from patched smali, do not rename them. SoundCloud's own download buttons,
 * which also sell Go+, are handled in {@code download}, not here.
 */
package app.revanced.extension.soundcloud.upsell;
